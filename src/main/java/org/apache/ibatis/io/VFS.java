/*
 *    Copyright 2009-2012 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.io;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

/**
 * Provides a very simple API for accessing resources within an application server.
 * 虚拟文件系统(VFS),用来读取服务器里的资源
 * 
 * @author Ben Gunter
 */
public abstract class VFS {
  private static final Log log = LogFactory.getLog(ResolverUtil.class);

  /** The built-in implementations. */
  //默认提供2个实现 JBoss6VFS,DefaultVFS
  public static final Class<?>[] IMPLEMENTATIONS = { JBoss6VFS.class, DefaultVFS.class };

  /** The list to which implementations are added by {@link #addImplClass(Class)}. */
  //这里是提供一个用户扩展点，可以让用户自定义VFS实现
  public static final List<Class<? extends VFS>> USER_IMPLEMENTATIONS = new ArrayList<Class<? extends VFS>>();

  /** Singleton instance. */
  //单例模式
  private static VFS instance;

  /**
   * Get the singleton {@link VFS} instance. If no {@link VFS} implementation can be found for the
   * current environment, then this method returns null.
   */
  //获取VFS实例，系统有的话，返回，没有的话，直接返回null
  @SuppressWarnings("unchecked")
  public static VFS getInstance() {
    if (instance != null) {
      return instance;
    }

    // Try the user implementations first, then the built-ins
    List<Class<? extends VFS>> impls = new ArrayList<Class<? extends VFS>>();
    //将两个vfs数组组装起来一起遍历，挨个看是否能 new出vfs实例
    impls.addAll(USER_IMPLEMENTATIONS);
    impls.addAll(Arrays.asList((Class<? extends VFS>[]) IMPLEMENTATIONS));

    // Try each implementation class until a valid one is found
    //遍历查找实现类，返回第一个找到的
    VFS vfs = null;
    for (int i = 0; vfs == null || !vfs.isValid(); i++) {
      Class<? extends VFS> impl = impls.get(i);
      try {
        vfs = impl.newInstance();
        if (vfs == null || !vfs.isValid()) {
          log.debug("VFS implementation " + impl.getName() +
              " is not valid in this environment.");
        }
      } catch (InstantiationException e) {
        log.error("Failed to instantiate " + impl, e);
        return null;
      } catch (IllegalAccessException e) {
        log.error("Failed to instantiate " + impl, e);
        return null;
      }
    }

    log.debug("Using VFS adapter " + vfs.getClass().getName());
    VFS.instance = vfs;
    return VFS.instance;
  }

  /**
   * Adds the specified class to the list of {@link VFS} implementations. Classes added in this
   * manner are tried in the order they are added and before any of the built-in implementations.
   * 添加实现类进入集合中，便于之后对 USER_IMPLEMENTATIONS 的处理
   * @param clazz The {@link VFS} implementation class to add.
   */
  public static void addImplClass(Class<? extends VFS> clazz) {
    if (clazz != null) {
      USER_IMPLEMENTATIONS.add(clazz);
    }
  }

  /** Get a class by name. If the class is not found then return null. */
  //根据classname加载类
  protected static Class<?> getClass(String className) {
    try {
      return Thread.currentThread().getContextClassLoader().loadClass(className);
//      return ReflectUtil.findClass(className);
    } catch (ClassNotFoundException e) {
      log.debug("Class not found: " + className);
      return null;
    }
  }

  /**
   * Get a method by name and parameter types. If the method is not found then return null.
   * 
   * @param clazz The class to which the method belongs.
   * @param methodName The name of the method.
   * @param parameterTypes The types of the parameters accepted by the method.
   */
  protected static Method getMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
    if (clazz == null) {
      return null;
    }
    try {
      return clazz.getMethod(methodName, parameterTypes);
    } catch (SecurityException e) {
      log.error("Security exception looking for method " + clazz.getName() + "." + methodName + ".  Cause: " + e);
      return null;
    } catch (NoSuchMethodException e) {
      log.error("Method not found " + clazz.getName() + "." + methodName + "." + methodName + ".  Cause: " + e);
      return null;
    }
  }

  /**
   * Invoke a method on an object and return whatever it returns.
   * 
   * @param method The method to invoke.
   * @param object The instance or class (for static methods) on which to invoke the method.
   * @param parameters The parameters to pass to the method.
   * @return Whatever the method returns.
   * @throws IOException If I/O errors occur
   * @throws StripesRuntimeException If anything else goes wrong
   */
  @SuppressWarnings("unchecked")
  protected static <T> T invoke(Method method, Object object, Object... parameters)
      throws IOException, RuntimeException {
    try {
      return (T) method.invoke(object, parameters);
    } catch (IllegalArgumentException e) {
      throw new RuntimeException(e);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    } catch (InvocationTargetException e) {
      if (e.getTargetException() instanceof IOException) {
        throw (IOException) e.getTargetException();
      } else {
        throw new RuntimeException(e);
      }
    }
  }

  /**
   * Get a list of {@link URL}s from the context classloader for all the resources found at the
   * specified path.
   * 
   * @param path The resource path.
   * @return A list of {@link URL}s, as returned by {@link ClassLoader#getResources(String)}.
   * @throws IOException If I/O errors occur
   */
  protected static List<URL> getResources(String path) throws IOException {
    return Collections.list(Thread.currentThread().getContextClassLoader().getResources(path));
  }

  /** Return true if the {@link VFS} implementation is valid for the current environment. */
  public abstract boolean isValid();

  /**
   * Recursively list the full resource path of all the resources that are children of the
   * resource identified by a URL.
   * 
   * @param url The URL that identifies the resource to list.
   * @param forPath The path to the resource that is identified by the URL. Generally, this is the
   *            value passed to {@link #getResources(String)} to get the resource URL.
   * @return A list containing the names of the child resources.
   * @throws IOException If I/O errors occur
   */
  protected abstract List<String> list(URL url, String forPath) throws IOException;

  /**
   * Recursively list the full resource path of all the resources that are children of all the
   * resources found at the specified path.
   * 
   * @param path The path of the resource(s) to list.
   * @return A list containing the names of the child resources.
   * @throws IOException If I/O errors occur
   */
  public List<String> list(String path) throws IOException {
    List<String> names = new ArrayList<String>();
    for (URL url : getResources(path)) {
      names.addAll(list(url, path));
    }
    return names;
  }
}
