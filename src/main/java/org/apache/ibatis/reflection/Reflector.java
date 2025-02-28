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
package org.apache.ibatis.reflection;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ReflectPermission;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.invoker.SetFieldInvoker;
import org.apache.ibatis.reflection.property.PropertyNamer;

/*
 * This class represents a cached set of class definition information that
 * allows for easy mapping between property names and getter/setter methods.
 */
/**
 * @author Clinton Begin
 */
/**
 * 反射器, 属性->getter/setter的映射器，而且加了缓存
 * 可参考ReflectorTest来理解这个类的用处
 *
 *
 * Reflector类是 整个反射模块的基础，每一个 reflector对象都对应一个类，在 reflector中缓存了反射操作需要使用的类的元信息
 *
 */
public class Reflector {

  private static boolean classCacheEnabled = true;
  private static final String[] EMPTY_STRING_ARRAY = new String[0];
  //这里用ConcurrentHashMap，多线程支持，作为一个缓存
  private static final Map<Class<?>, Reflector> REFLECTOR_MAP = new ConcurrentHashMap<Class<?>, Reflector>();

  private Class<?> type;
  //getter的属性列表
  //可读属性的名称集合，可读属性就是存在相对应的getter方法的属性，初始值为空数组
  private String[] readablePropertyNames = EMPTY_STRING_ARRAY;
  //setter的属性列表
  //可写属性的名称集合，可写属性就是存在相对应的setter方法的属性，初始值为空数组
  private String[] writeablePropertyNames = EMPTY_STRING_ARRAY;
  //setter的方法列表
  private Map<String, Invoker> setMethods = new HashMap<String, Invoker>();
  //getter的方法列表
  private Map<String, Invoker> getMethods = new HashMap<String, Invoker>();
  //setter的类型列表
  private Map<String, Class<?>> setTypes = new HashMap<String, Class<?>>();
  //getter的类型列表
  private Map<String, Class<?>> getTypes = new HashMap<String, Class<?>>();
  //构造函数
  private Constructor<?> defaultConstructor;
  //记录了所有属性名称的集合
  private Map<String, String> caseInsensitivePropertyMap = new HashMap<String, String>();

  private Reflector(Class<?> clazz) {
    type = clazz;
    //加入构造函数
    addDefaultConstructor(clazz);
    //加入getter
    addGetMethods(clazz);//处理clazz中的getter方法，填充getterMethods集合和getTypes集合
    //加入setter
    addSetMethods(clazz);//处理clazz中的setter方法，填充setterMethods集合和getTypes集合
    //加入字段
    addFields(clazz);
    //根据getMethods/setmethods 集合，初始化 可读、可写属性的名称集合
    readablePropertyNames = getMethods.keySet().toArray(new String[getMethods.keySet().size()]);
    writeablePropertyNames = setMethods.keySet().toArray(new String[setMethods.keySet().size()]);
    //初始化 caseInsensitivePropertyMap 集合，其中记录了所有大写格式的属性名称
    for (String propName : readablePropertyNames) {
        //这里为了能找到某一个属性，就把他变成大写作为map的key。。。
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
    for (String propName : writeablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
  }

  private void addDefaultConstructor(Class<?> clazz) {
    Constructor<?>[] consts = clazz.getDeclaredConstructors();
    for (Constructor<?> constructor : consts) {
      if (constructor.getParameterTypes().length == 0) {
        if (canAccessPrivateMethods()) {
          try {
            constructor.setAccessible(true);
          } catch (Exception e) {
            // Ignored. This is only a final precaution, nothing we can do.
          }
        }
        if (constructor.isAccessible()) {
          this.defaultConstructor = constructor;
        }
      }
    }
  }

  //完成对 addGetMethod 和 getTypes 集合的填充
  private void addGetMethods(Class<?> cls) {
    Map<String, List<Method>> conflictingGetters = new HashMap<String, List<Method>>();
    //这里getter和setter都调用了getClassMethods，有点浪费效率了。不妨把addGetMethods,addSetMethods合并成一个方法叫addMethods
    //conflictingGetters 集合的K是  属性名称，v是 属性名相应的 getter方法合集，因为子类可能覆盖父类的getter方法，
    // 所以同一个属性名可能存在多个getter方法
    Method[] methods = getClassMethods(cls);
    for (Method method : methods) {
      String name = method.getName();
      //将getter方法查找出来，记录在集合中
      if (name.startsWith("get") && name.length() > 3) {
        if (method.getParameterTypes().length == 0) {
          name = PropertyNamer.methodToProperty(name);
          addMethodConflict(conflictingGetters, name, method);
        }
      } else if (name.startsWith("is") && name.length() > 2) {
        if (method.getParameterTypes().length == 0) {
          name = PropertyNamer.methodToProperty(name);
          addMethodConflict(conflictingGetters, name, method);
        }
      }
    }
    resolveGetterConflicts(conflictingGetters);
  }


  //主要是处理 子类覆盖父类中的同名方法，但是返回值不同的情况
  //例如  父类的返回值是 List  子类的返回值是 ArrayList 这种在生成签名的时候会生成不一样的签名，但是我们认为这是同一个返回值
  //在这里进行处理
  private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {
    //遍历map集合  propName 是属性名，List<Method>>是针对同一属性名的不同方法，重载或者重写的方法
    for (String propName : conflictingGetters.keySet()) {
      List<Method> getters = conflictingGetters.get(propName);
      Iterator<Method> iterator = getters.iterator();
      Method firstMethod = iterator.next();
      //对于该属性只有一个方法时，当然没什么需要处理的
      if (getters.size() == 1) {
        addGetMethod(propName, firstMethod);
      } else {
        //当对于同一属性，有多个getter方法时候，则需要依次比较这些方法中的返回值
        //以下两个变量用于迭代中的中间变量，在 if条件符合的时候传递值
        Method getter = firstMethod;//最适合作为getter方法的method
        Class<?> getterType = firstMethod.getReturnType();//当前属性最适合的返回值类型
        //map中的value,也就是list集合，遍历该list集合
        while (iterator.hasNext()) {
          Method method = iterator.next();
          Class<?> methodType = method.getReturnType();
          if (methodType.equals(getterType)) {
            throw new ReflectionException("Illegal overloaded getter method with ambiguous type for property " 
                + propName + " in class " + firstMethod.getDeclaringClass()
                + ".  This breaks the JavaBeans " + "specification and can cause unpredicatble results.");
          } else if (methodType.isAssignableFrom(getterType)) {
            // OK getter type is descendant
          } else if (getterType.isAssignableFrom(methodType)) {
            getter = method;
            getterType = methodType;
          } else {
            throw new ReflectionException("Illegal overloaded getter method with ambiguous type for property " 
                + propName + " in class " + firstMethod.getDeclaringClass()
                + ".  This breaks the JavaBeans " + "specification and can cause unpredicatble results.");
          }
        }
        addGetMethod(propName, getter);
      }
    }
  }

  private void addGetMethod(String name, Method method) {
    //判断属性名是否合法
    if (isValidPropertyName(name)) {
      //填充 getMethods getTypes 集合
      getMethods.put(name, new MethodInvoker(method));
      getTypes.put(name, method.getReturnType());
    }
  }

  private void addSetMethods(Class<?> cls) {
    Map<String, List<Method>> conflictingSetters = new HashMap<String, List<Method>>();
    Method[] methods = getClassMethods(cls);
    for (Method method : methods) {
      String name = method.getName();
      if (name.startsWith("set") && name.length() > 3) {
        if (method.getParameterTypes().length == 1) {
          name = PropertyNamer.methodToProperty(name);
          addMethodConflict(conflictingSetters, name, method);
        }
      }
    }
    resolveSetterConflicts(conflictingSetters);
  }

  private void addMethodConflict(Map<String, List<Method>> conflictingMethods, String name, Method method) {
    List<Method> list = conflictingMethods.get(name);
    if (list == null) {
      list = new ArrayList<Method>();
      conflictingMethods.put(name, list);
    }
    list.add(method);
  }

  private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
    for (String propName : conflictingSetters.keySet()) {
      List<Method> setters = conflictingSetters.get(propName);
      Method firstMethod = setters.get(0);
      if (setters.size() == 1) {
        addSetMethod(propName, firstMethod);
      } else {
        Class<?> expectedType = getTypes.get(propName);
        if (expectedType == null) {
          throw new ReflectionException("Illegal overloaded setter method with ambiguous type for property "
              + propName + " in class " + firstMethod.getDeclaringClass() + ".  This breaks the JavaBeans " +
              "specification and can cause unpredicatble results.");
        } else {
          Iterator<Method> methods = setters.iterator();
          Method setter = null;
          while (methods.hasNext()) {
            Method method = methods.next();
            if (method.getParameterTypes().length == 1
                && expectedType.equals(method.getParameterTypes()[0])) {
              setter = method;
              break;
            }
          }
          if (setter == null) {
            throw new ReflectionException("Illegal overloaded setter method with ambiguous type for property "
                + propName + " in class " + firstMethod.getDeclaringClass() + ".  This breaks the JavaBeans " +
                "specification and can cause unpredicatble results.");
          }
          addSetMethod(propName, setter);
        }
      }
    }
  }

  private void addSetMethod(String name, Method method) {
    if (isValidPropertyName(name)) {
      setMethods.put(name, new MethodInvoker(method));
      setTypes.put(name, method.getParameterTypes()[0]);
    }
  }

  private void addFields(Class<?> clazz) {
    //获取类中生命的全部字段
    Field[] fields = clazz.getDeclaredFields();
    for (Field field : fields) {
      //进行安全检查
      if (canAccessPrivateMethods()) {
        try {
          field.setAccessible(true);
        } catch (Exception e) {
          // Ignored. This is only a final precaution, nothing we can do.
        }
      }
      if (field.isAccessible()) {
        if (!setMethods.containsKey(field.getName())) {
          // issue #379 - removed the check for final because JDK 1.5 allows
          // modification of final fields through reflection (JSR-133). (JGB)
          // pr #16 - final static can only be set by the classloader
          int modifiers = field.getModifiers();
          //过滤掉被 final 和 static 同时修饰的字段
          if (!(Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers))) {
            //填充 addSetField 集合
            addSetField(field);
          }
        }
        if (!getMethods.containsKey(field.getName())) {
          addGetField(field);
        }
      }
    }
    if (clazz.getSuperclass() != null) {
      //递归，向上处理父类中的字段
      addFields(clazz.getSuperclass());
    }
  }

  private void addSetField(Field field) {
    if (isValidPropertyName(field.getName())) {
      setMethods.put(field.getName(), new SetFieldInvoker(field));
      setTypes.put(field.getName(), field.getType());
    }
  }

  private void addGetField(Field field) {
    if (isValidPropertyName(field.getName())) {
      getMethods.put(field.getName(), new GetFieldInvoker(field));
      getTypes.put(field.getName(), field.getType());
    }
  }

  //判断属性名是否合法，竟然是通过属性名的字符串规则进行验证的
  private boolean isValidPropertyName(String name) {
    return !(name.startsWith("$") || "serialVersionUID".equals(name) || "class".equals(name));
  }

  /*
   * This method returns an array containing all methods
   * declared in this class and any superclass.
   * We use this method, instead of the simpler Class.getMethods(),
   * because we want to look for private methods as well.
   * 得到所有方法，包括private方法，包括父类方法.包括接口方法
   *获取当前类以及其父类中定义的所有方法的唯一签名以及相对应的method对象
   * @param cls The class
   * @return An array containing all methods in this class
   */
  private Method[] getClassMethods(Class<?> cls) {
    Map<String, Method> uniqueMethods = new HashMap<String, Method>();
    Class<?> currentClass = cls;
    //不停向上获取父类
    while (currentClass != null) {
      //记录当前拿到的类中声明的全部方法
      addUniqueMethods(uniqueMethods, currentClass.getDeclaredMethods());

      // we also need to look for interface methods - 
      // because the class may be abstract
      //记录类中的全部接口
      Class<?>[] interfaces = currentClass.getInterfaces();
      for (Class<?> anInterface : interfaces) {
        addUniqueMethods(uniqueMethods, anInterface.getMethods());
      }

      currentClass = currentClass.getSuperclass();//向上获取父类，将父类赋值给当前对象，继续循环
    }

    Collection<Method> methods = uniqueMethods.values();
    //转换成数组返回
    return methods.toArray(new Method[methods.size()]);
  }

  //这里会对每一个方法方法生成唯一签名
  private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {
    for (Method currentMethod : methods) {
      //判断当前方法是不是桥接方法
      if (!currentMethod.isBridge()) {
          //取得签名
        String signature = getSignature(currentMethod);
        // check to see if the method is already known
        // if it is known, then an extended class must have
        // overridden a method
        //检查当前类中是否已经包含该，如果有的话，证明子类覆盖了父类中的方法。那么父类的该方法无需再次添加
        if (!uniqueMethods.containsKey(signature)) {
          if (canAccessPrivateMethods()) {
            try {
              currentMethod.setAccessible(true);
            } catch (Exception e) {
              // Ignored. This is only a final precaution, nothing we can do.
            }
          }
          //将获得签名和方法一同存入map,建立映射关系
          uniqueMethods.put(signature, currentMethod);
        }
      }
    }
  }

  //生成方法的签名，其实就是将获取的属性拼接字符串返回
  //格式为：返回值类型#方法名：参数名，参数名，参数名
  //实例    String#getXXX:参数1，参数2
  //这样得到的方法签名是全局唯一的
  private String getSignature(Method method) {
    StringBuilder sb = new StringBuilder();
    Class<?> returnType = method.getReturnType();
    if (returnType != null) {
      sb.append(returnType.getName()).append('#');
    }
    sb.append(method.getName());
    Class<?>[] parameters = method.getParameterTypes();
    for (int i = 0; i < parameters.length; i++) {
      if (i == 0) {
        sb.append(':');
      } else {
        sb.append(',');
      }
      sb.append(parameters[i].getName());
    }
    return sb.toString();
  }

  private static boolean canAccessPrivateMethods() {
    try {
      SecurityManager securityManager = System.getSecurityManager();
      if (null != securityManager) {
        securityManager.checkPermission(new ReflectPermission("suppressAccessChecks"));
      }
    } catch (SecurityException e) {
      return false;
    }
    return true;
  }

  /*
   * Gets the name of the class the instance provides information for
   *
   * @return The class name
   */
  public Class<?> getType() {
    return type;
  }

  public Constructor<?> getDefaultConstructor() {
    if (defaultConstructor != null) {
      return defaultConstructor;
    } else {
      throw new ReflectionException("There is no default constructor for " + type);
    }
  }

  public boolean hasDefaultConstructor() {
    return defaultConstructor != null;
  }

  public Invoker getSetInvoker(String propertyName) {
    Invoker method = setMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  public Invoker getGetInvoker(String propertyName) {
    Invoker method = getMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  /*
   * Gets the type for a property setter
   *
   * @param propertyName - the name of the property
   * @return The Class of the propery setter
   */
  public Class<?> getSetterType(String propertyName) {
    Class<?> clazz = setTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /*
   * Gets the type for a property getter
   *
   * @param propertyName - the name of the property
   * @return The Class of the propery getter
   */
  public Class<?> getGetterType(String propertyName) {
    Class<?> clazz = getTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /*
   * Gets an array of the readable properties for an object
   *
   * @return The array
   */
  public String[] getGetablePropertyNames() {
    return readablePropertyNames;
  }

  /*
   * Gets an array of the writeable properties for an object
   *
   * @return The array
   */
  public String[] getSetablePropertyNames() {
    return writeablePropertyNames;
  }

  /*
   * Check to see if a class has a writeable property by name
   *
   * @param propertyName - the name of the property to check
   * @return True if the object has a writeable property by the name
   */
  public boolean hasSetter(String propertyName) {
    return setMethods.keySet().contains(propertyName);
  }

  /*
   * Check to see if a class has a readable property by name
   *
   * @param propertyName - the name of the property to check
   * @return True if the object has a readable property by the name
   */
  public boolean hasGetter(String propertyName) {
    return getMethods.keySet().contains(propertyName);
  }

  public String findPropertyName(String name) {
    return caseInsensitivePropertyMap.get(name.toUpperCase(Locale.ENGLISH));
  }

  /*
   * Gets an instance of ClassInfo for the specified class.
   * 得到某个类的反射器，是静态方法，而且要缓存，又要多线程，所以REFLECTOR_MAP是一个ConcurrentHashMap
   *
   * @param clazz The class for which to lookup the method cache.
   * @return The method cache for the class
   */
  public static Reflector forClass(Class<?> clazz) {
    if (classCacheEnabled) {
      // synchronized (clazz) removed see issue #461
        //对于每个类来说，我们假设它是不会变的，这样可以考虑将这个类的信息(构造函数，getter,setter,字段)加入缓存，以提高速度
      Reflector cached = REFLECTOR_MAP.get(clazz);
      if (cached == null) {
        cached = new Reflector(clazz);
        REFLECTOR_MAP.put(clazz, cached);
      }
      return cached;
    } else {
      return new Reflector(clazz);
    }
  }

  public static void setClassCacheEnabled(boolean classCacheEnabled) {
    Reflector.classCacheEnabled = classCacheEnabled;
  }

  public static boolean isClassCacheEnabled() {
    return classCacheEnabled;
  }
}
