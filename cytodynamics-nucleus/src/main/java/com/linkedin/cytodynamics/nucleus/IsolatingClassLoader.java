/**
 * Copyright 2018-2019 LinkedIn Corporation
 * All Rights Reserved.
 *
 * Licensed under the BSD 2-Clause License (the "License").
 * See LICENSE in the project root for license information.
 */
package com.linkedin.cytodynamics.nucleus;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Enumeration;
import java.util.List;
import java.util.function.BiFunction;


/**
 * Isolating classloader, used to separate classes.
 */
class IsolatingClassLoader extends URLClassLoader {
  private static final Logger LOGGER = LogApiAdapter.getLogger(IsolatingClassLoader.class);

  private final DelegateRelationship parentRelationship;
  private final List<DelegateRelationship> fallbackDelegates;

  /**
   * @param classpath classpath for this classloader
   * @param parentRelationship non-null primary {@link DelegateRelationship}
   * @param fallbackDelegates list of fallback {@link ClassLoader}s; may be empty, but must be non-null
   */
  IsolatingClassLoader(URL[] classpath, DelegateRelationship parentRelationship,
      List<DelegateRelationship> fallbackDelegates) {
    /*
     * Use the classloader from the parent relationship as the parent classloader, since that will be checked first when
     * loading a class.
     */
    super(classpath, parentRelationship.getDelegateClassLoader());
    this.parentRelationship = parentRelationship;
    this.fallbackDelegates = fallbackDelegates;
  }

  enum IsolationBehaviors {
    USE_CHILD_CLASS((delegate, child) -> child),
    USE_DELEGATE_CLASS((delegate, child) -> delegate),
    USE_DELEGATE_AND_LOG((delegate, child) -> {
      LOGGER.warn("Class " + delegate.getName()
          + " used from the delegate classloader would not be visible when running under full isolation.");
      return delegate;
    }),
    CLASS_NOT_FOUND((delegate, child) -> null);

    IsolationBehaviors(BiFunction<Class<?>, Class<?>, Class<?>> function) {
      _function = function;
    }

    private final BiFunction<Class<?>, Class<?>, Class<?>> _function;

    public Class<?> getEffectiveClass(Class<?> delegate, Class<?> child) {
      return _function.apply(delegate, child);
    }
  }

  private static final IsolationBehaviors[][] ISOLATION_BEHAVIORS = new IsolationBehaviors[][]{
      // NONE
      {
          IsolationBehaviors.CLASS_NOT_FOUND,       // Not in delegate or child
          IsolationBehaviors.USE_CHILD_CLASS,       // Only in child
          IsolationBehaviors.USE_DELEGATE_CLASS,    // Only in delegate
          IsolationBehaviors.USE_CHILD_CLASS        // In delegate and child
      },
      // TRANSITIONAL
      {
          IsolationBehaviors.CLASS_NOT_FOUND,       // Not in delegate or child
          IsolationBehaviors.USE_CHILD_CLASS,       // Only in child
          IsolationBehaviors.USE_DELEGATE_AND_LOG,  // Only in delegate
          IsolationBehaviors.USE_CHILD_CLASS        // In delegate and child
      },
      // FULL
      {
          IsolationBehaviors.CLASS_NOT_FOUND,       // Not in delegate or child
          IsolationBehaviors.USE_CHILD_CLASS,       // Only in child
          IsolationBehaviors.CLASS_NOT_FOUND,       // Only in delegate
          IsolationBehaviors.USE_CHILD_CLASS        // In delegate and child
      }
  };

  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    synchronized (getClassLoadingLock(name)) {
      // check if the class has already been loaded
      Class<?> cl = findLoadedClass(name);

      if (cl == null) {
        // try to load the class using the parent
        cl = tryLoadClassWithDelegate(name, this.parentRelationship);
      }

      if (cl == null) {
        // try to load the class using a fallback
        for (DelegateRelationship fallbackDelegate : this.fallbackDelegates) {
          cl = tryLoadClassWithDelegate(name, fallbackDelegate);
          if (cl != null) {
            break;
          }
        }
      }

      if (cl != null) {
        if (resolve) {
          doResolveClass(cl);
        }
        return cl;
      } else {
        // got through parent and fallback delegates but could not find the class
        throw new ClassNotFoundException("Could not load class for name " + name);
      }
    }
  }

  /**
   * This only loads resources from the classpath associated with this classloader. It does not load resources from
   * any parent classloaders.
   *
   * TODO: Is it useful to be able to whitelist certain resources to be loaded from a parent?
   */
  @Override
  public URL getResource(String name) {
    return findResource(name);
  }

  /**
   * This only loads resources from the classpath associated with this classloader. It does not load resources from
   * any parent classloaders.
   *
   * TODO: Is it useful to be able to whitelist certain resources to be loaded from a parent?
   */
  @Override
  public Enumeration<URL> getResources(String name) throws IOException {
    return findResources(name);
  }

  /*
   * It is currently unnecessary to override getResourceAsStream, since it uses getResource to get a resource, and
   * getResource is overridden by this class.
   */

  /**
   * Try to load a class corresponding to an individual {@link DelegateRelationship}.
   *
   * @param name name of the class to load
   * @param delegateRelationship {@link DelegateRelationship} to use for loading
   * @return {@link Class} corresponding to {@code name} if a class could be resolved corresponding to the
   * {@code parentRelationship}; null otherwise
   */
  private Class<?> tryLoadClassWithDelegate(String name, DelegateRelationship delegateRelationship) {
    Class<?> delegateClass = null;
    Class<?> childClass = null;

    // Is the class blacklisted from being loaded from the delegate?
    boolean isBlacklisted = false;
    for (GlobMatcher blacklistedClassPattern : delegateRelationship.getBlacklistedClassPatterns()) {
      if (blacklistedClassPattern.matches(name)) {
        isBlacklisted = true;
        break;
      }
    }

    // Is it already loaded in the delegate class loader?
    try {
      if (!isBlacklisted) {
        delegateClass = delegateRelationship.getDelegateClassLoader().loadClass(name);

        // Is it part of the exported API or part of core Java?
        if (delegateClass.isAnnotationPresent(Api.class)) {
          return delegateClass;
        } else {
          // Is it delegate preferred?
          for (GlobMatcher delegatePreferredClassPattern : delegateRelationship.getDelegatePreferredClassPatterns()) {
            if (delegatePreferredClassPattern.matches(name)) {
              return delegateClass;
            }
          }
        }
      }
    } catch (ClassNotFoundException | NoClassDefFoundError e) {
      // It doesn't exist in the delegate class loader, try to load it.
    }

    try {
      childClass = findClass(name);
    } catch (ClassNotFoundException | NoClassDefFoundError e) {
      // Ignored
    }

    Class<?> returnValue =
        getIsolationBehavior(delegateRelationship.getIsolationLevel(), delegateClass, childClass).getEffectiveClass(
            delegateClass, childClass);

    // Is it whitelisted and present in the delegate class loader but hidden due to the isolation behavior?
    if (returnValue == null && delegateClass != null) {
      for (GlobMatcher whitelistedClassPattern : delegateRelationship.getWhitelistedClassPatterns()) {
        if (whitelistedClassPattern.matches(name)) {
          return delegateClass;
        }
      }
    }

    return returnValue;
  }

  private static IsolationBehaviors getIsolationBehavior(IsolationLevel isolationLevel, Class<?> delegateClass,
      Class<?> childClass) {
    boolean hasDelegateClass = delegateClass != null;
    boolean hasChildClass = childClass != null;
    int behaviorIndex = (hasDelegateClass ? 0b10 : 0b00) | (hasChildClass ? 0b01 : 0b00);

    return ISOLATION_BEHAVIORS[isolationLevel.ordinal()][behaviorIndex];
  }

  /**
   * Package-private for testing purposes, since resolveClass is protected within {@link ClassLoader}.
   */
  void doResolveClass(Class<?> cl) {
    resolveClass(cl);
  }
}
