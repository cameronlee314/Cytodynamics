/**
 * Copyright (C) 2014-2018 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.linkedin.cytodynamics.nucleus;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Set;
import java.util.function.BiFunction;


/**
 * Isolating classloader, used to separate classes.
 */
class IsolatingClassLoader extends URLClassLoader {
  private static final Logger LOGGER = LogApiAdapter.getLogger(IsolatingClassLoader.class);

  private ClassLoader parent;
  private IsolationLevel isolationLevel;
  private Set<GlobMatcher> parentPreferredClassPatterns;
  private Set<GlobMatcher> blacklistedClassPatterns;
  private Set<GlobMatcher> whitelistedClassPatterns;

  IsolatingClassLoader(URL[] classpath, ClassLoader parent, IsolationLevel isolationLevel,
      Set<GlobMatcher> parentPreferredClassPatterns, Set<GlobMatcher> whitelistedClassPatterns,
      Set<GlobMatcher> blacklistedClassPatterns) {
    super(classpath , parent);
    this.parent = parent;
    this.isolationLevel = isolationLevel;
    this.parentPreferredClassPatterns = parentPreferredClassPatterns;
    this.blacklistedClassPatterns = blacklistedClassPatterns;
    this.whitelistedClassPatterns = whitelistedClassPatterns;
  }

  enum IsolationBehaviors {
    USE_CHILD_CLASS((parent, child) -> child),
    USE_PARENT_CLASS((parent, child) -> parent),
    USE_PARENT_AND_LOG((parent, child) -> {
      LOGGER.warn("Class " + parent.getName() + " used from the parent classloader would not be visible when running under full isolation.");
      return parent;
    }),
    CLASS_NOT_FOUND((parent, child) -> null);

    IsolationBehaviors(BiFunction<Class<?>, Class<?>, Class<?>> function) {
      _function = function;
    }

    private final BiFunction<Class<?>, Class<?>, Class<?>> _function;

    public Class<?> getEffectiveClass(Class<?> parent, Class<?> child) {
      return _function.apply(parent, child);
    }
  };

  private static final IsolationBehaviors[][] ISOLATION_BEHAVIORS = new IsolationBehaviors[][]{
      // NONE
      {
          IsolationBehaviors.CLASS_NOT_FOUND,     // Not in parent or child
          IsolationBehaviors.USE_CHILD_CLASS,     // Only in child
          IsolationBehaviors.USE_PARENT_CLASS,    // Only in parent
          IsolationBehaviors.USE_CHILD_CLASS      // In parent and child
      },
      // TRANSITIONAL
      {
          IsolationBehaviors.CLASS_NOT_FOUND,     // Not in parent or child
          IsolationBehaviors.USE_CHILD_CLASS,     // Only in child
          IsolationBehaviors.USE_PARENT_AND_LOG,  // Only in parent
          IsolationBehaviors.USE_CHILD_CLASS      // In parent and child
      },
      // FULL
      {
          IsolationBehaviors.CLASS_NOT_FOUND,     // Not in parent or child
          IsolationBehaviors.USE_CHILD_CLASS,     // Only in child
          IsolationBehaviors.CLASS_NOT_FOUND,     // Only in parent
          IsolationBehaviors.USE_CHILD_CLASS      // In parent and child
      }
  };

  @Override
  public Class<?> loadClass(String name) throws ClassNotFoundException {
    Class<?> parentClass = null;
    Class<?> childClass = null;

    // Is the class blacklisted from being loaded from the parent?
    boolean isBlacklisted = false;
    for (GlobMatcher blacklistedClassPattern : blacklistedClassPatterns) {
      if (blacklistedClassPattern.matches(name)) {
        isBlacklisted = true;
        break;
      }
    }

    // Is it already loaded in the parent class loader?
    try {
      if (!isBlacklisted) {
        parentClass = parent.loadClass(name);

        // Is it part of the exported API or part of core Java?
        if (parentClass.isAnnotationPresent(Api.class)) {
          return parentClass;
        } else {
          // Is it parent preferred?
          for (GlobMatcher parentPreferredClassPattern : parentPreferredClassPatterns) {
            if (parentPreferredClassPattern.matches(name)) {
              return parentClass;
            }
          }
        }
      }
    } catch (ClassNotFoundException | NoClassDefFoundError e) {
      // It doesn't exist in the parent class loader, try to load it.
    }

    try {
      childClass = findClass(name);
    } catch (ClassNotFoundException | NoClassDefFoundError e) {
      // Ignored
    }

    Class<?> returnValue = getIsolationBehavior(isolationLevel, parentClass, childClass).getEffectiveClass(parentClass, childClass);

    // Is it whitelisted and present in the parent class loader but hidden due to the isolation behavior?
    if (returnValue == null && parentClass != null) {
      for (GlobMatcher whitelistedClassPattern : whitelistedClassPatterns) {
        if (whitelistedClassPattern.matches(name)) {
          return parentClass;
        }
      }
    }

    if (returnValue != null) {
      return returnValue;
    } else {
      throw new ClassNotFoundException();
    }
  }

  @Override
  protected Class<?> findClass(String name) throws ClassNotFoundException {
    return super.findClass(name);
  }

  private static IsolationBehaviors getIsolationBehavior(IsolationLevel isolationLevel, Class<?> parentClass,
      Class<?> childClass) {
    boolean hasParentClass = parentClass != null;
    boolean hasChildClass = childClass != null;
    int behaviorIndex = (hasParentClass ? 0b10 : 0b00) | (hasChildClass ? 0b01 : 0b00);

    return ISOLATION_BEHAVIORS[isolationLevel.ordinal()][behaviorIndex];
  }
}
