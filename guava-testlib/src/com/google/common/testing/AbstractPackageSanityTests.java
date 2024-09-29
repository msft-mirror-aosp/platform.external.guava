/*
 * Copyright (C) 2012 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.common.testing;

import static com.google.common.base.Predicates.and;
import static com.google.common.base.Predicates.not;
import static com.google.common.testing.AbstractPackageSanityTests.Chopper.suffix;

import com.google.common.annotations.GwtIncompatible;
import com.google.common.annotations.J2ktIncompatible;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.reflect.ClassPath;
import com.google.common.testing.NullPointerTester.Visibility;
import com.google.j2objc.annotations.J2ObjCIncompatible;
import java.io.IOException;
import java.io.Serializable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import junit.framework.TestCase;
import org.junit.Test;

/**
 * Automatically runs sanity checks against top level classes in the same package of the test that
 * extends {@code AbstractPackageSanityTests}. Currently sanity checks include {@link
 * NullPointerTester}, {@link EqualsTester} and {@link SerializableTester}. For example:
 *
 * <pre>
 * public class PackageSanityTests extends AbstractPackageSanityTests {}
 * </pre>
 *
 * <p>Note that only top-level classes with either a non-private constructor or a non-private static
 * factory method to construct instances can have their instance methods checked. For example:
 *
 * <pre>
 * public class Address {
 *   private final String city;
 *   private final String state;
 *   private final String zipcode;
 *
 *   public Address(String city, String state, String zipcode) {...}
 *
 *   {@literal @Override} public boolean equals(Object obj) {...}
 *   {@literal @Override} public int hashCode() {...}
 *   ...
 * }
 * </pre>
 *
 * <p>No cascading checks are performed against the return values of methods unless the method is a
 * static factory method. Neither are semantics of mutation methods such as {@code
 * someList.add(obj)} checked. For more detailed discussion of supported and unsupported cases, see
 * {@link #testEquals}, {@link #testNulls} and {@link #testSerializable}.
 *
 * <p>For testing against the returned instances from a static factory class, such as
 *
 * <pre>
 * interface Book {...}
 * public class Books {
 *   public static Book hardcover(String title) {...}
 *   public static Book paperback(String title) {...}
 * }
 * </pre>
 *
 * <p>please use {@link ClassSanityTester#forAllPublicStaticMethods}.
 *
 * <p>If not all classes on the classpath should be covered, {@link #ignoreClasses} can be used to
 * exclude certain classes. As a special case, classes with an underscore in the name (like {@code
 * AutoValue_Foo}) can be excluded using <code>ignoreClasses({@link #UNDERSCORE_IN_NAME})</code>.
 *
 * <p>{@link #setDefault} allows subclasses to specify default values for types.
 *
 * <p>This class incurs IO because it scans the classpath and reads classpath resources.
 *
 * @author Ben Yu
 * @since 14.0
 */
// TODO: Switch to JUnit 4 and use @Parameterized and @BeforeClass
// Note: @Test annotations are deliberate, as some subclasses specify @RunWith(JUnit4).
@GwtIncompatible
@J2ktIncompatible
@J2ObjCIncompatible // com.google.common.reflect.ClassPath
@ElementTypesAreNonnullByDefault
public abstract class AbstractPackageSanityTests extends TestCase {

  /**
   * A predicate that matches classes with an underscore in the class name. This can be used with
   * {@link #ignoreClasses} to exclude generated classes, such as the {@code AutoValue_Foo} classes
   * generated by <a href="https://github.com/google/auto/tree/master/value">AutoValue</a>.
   *
   * @since 19.0
   */
  public static final Predicate<Class<?>> UNDERSCORE_IN_NAME =
      (Class<?> c) -> c.getSimpleName().contains("_");

  /* The names of the expected method that tests null checks. */
  private static final ImmutableList<String> NULL_TEST_METHOD_NAMES =
      ImmutableList.of(
          "testNulls", "testNull",
          "testNullPointers", "testNullPointer",
          "testNullPointerExceptions", "testNullPointerException");

  /* The names of the expected method that tests serializable. */
  private static final ImmutableList<String> SERIALIZABLE_TEST_METHOD_NAMES =
      ImmutableList.of(
          "testSerializable", "testSerialization",
          "testEqualsAndSerializable", "testEqualsAndSerialization");

  /* The names of the expected method that tests equals. */
  private static final ImmutableList<String> EQUALS_TEST_METHOD_NAMES =
      ImmutableList.of(
          "testEquals",
          "testEqualsAndHashCode",
          "testEqualsAndSerializable",
          "testEqualsAndSerialization",
          "testEquality");

  private static final Chopper TEST_SUFFIX =
      suffix("Test").or(suffix("Tests")).or(suffix("TestCase")).or(suffix("TestSuite"));

  private final Logger logger = Logger.getLogger(getClass().getName());
  private final ClassSanityTester tester = new ClassSanityTester();
  private Visibility visibility = Visibility.PACKAGE;
  private Predicate<Class<?>> classFilter =
      (Class<?> cls) -> visibility.isVisible(cls.getModifiers());

  /**
   * Restricts the sanity tests for public API only. By default, package-private API are also
   * covered.
   */
  protected final void publicApiOnly() {
    visibility = Visibility.PUBLIC;
  }

  /**
   * Tests all top-level {@link Serializable} classes in the package. For a serializable Class
   * {@code C}:
   *
   * <ul>
   *   <li>If {@code C} explicitly implements {@link Object#equals}, the deserialized instance will
   *       be checked to be equal to the instance before serialization.
   *   <li>If {@code C} doesn't explicitly implement {@code equals} but instead inherits it from a
   *       superclass, no equality check is done on the deserialized instance because it's not clear
   *       whether the author intended for the class to be a value type.
   *   <li>If a constructor or factory method takes a parameter whose type is interface, a dynamic
   *       proxy will be passed to the method. It's possible that the method body expects an
   *       instance method of the passed-in proxy to be of a certain value yet the proxy isn't aware
   *       of the assumption, in which case the equality check before and after serialization will
   *       fail.
   *   <li>If the constructor or factory method takes a parameter that {@link
   *       AbstractPackageSanityTests} doesn't know how to construct, the test will fail.
   *   <li>If there is no visible constructor or visible static factory method declared by {@code
   *       C}, {@code C} is skipped for serialization test, even if it implements {@link
   *       Serializable}.
   *   <li>Serialization test is not performed on method return values unless the method is a
   *       visible static factory method whose return type is {@code C} or {@code C}'s subtype.
   * </ul>
   *
   * <p>In all cases, if {@code C} needs custom logic for testing serialization, you can add an
   * explicit {@code testSerializable()} test in the corresponding {@code CTest} class, and {@code
   * C} will be excluded from automated serialization test performed by this method.
   */
  @Test
  public void testSerializable() throws Exception {
    // TODO: when we use @BeforeClass, we can pay the cost of class path scanning only once.
    for (Class<?> classToTest :
        findClassesToTest(loadClassesInPackage(), SERIALIZABLE_TEST_METHOD_NAMES)) {
      if (Serializable.class.isAssignableFrom(classToTest)) {
        try {
          Object instance = tester.instantiate(classToTest);
          if (instance != null) {
            if (isEqualsDefined(classToTest)) {
              SerializableTester.reserializeAndAssert(instance);
            } else {
              SerializableTester.reserialize(instance);
            }
          }
        } catch (Throwable e) {
          throw sanityError(classToTest, SERIALIZABLE_TEST_METHOD_NAMES, "serializable test", e);
        }
      }
    }
  }

  /**
   * Performs {@link NullPointerTester} checks for all top-level classes in the package. For a class
   * {@code C}
   *
   * <ul>
   *   <li>All visible static methods are checked such that passing null for any parameter that's
   *       not annotated nullable (according to the rules of {@link NullPointerTester}) should throw
   *       {@link NullPointerException}.
   *   <li>If there is any visible constructor or visible static factory method declared by the
   *       class, all visible instance methods will be checked too using the instance created by
   *       invoking the constructor or static factory method.
   *   <li>If the constructor or factory method used to construct instance takes a parameter that
   *       {@link AbstractPackageSanityTests} doesn't know how to construct, the test will fail.
   *   <li>If there is no visible constructor or visible static factory method declared by {@code
   *       C}, instance methods are skipped for nulls test.
   *   <li>Nulls test is not performed on method return values unless the method is a visible static
   *       factory method whose return type is {@code C} or {@code C}'s subtype.
   * </ul>
   *
   * <p>In all cases, if {@code C} needs custom logic for testing nulls, you can add an explicit
   * {@code testNulls()} test in the corresponding {@code CTest} class, and {@code C} will be
   * excluded from the automated null tests performed by this method.
   */
  @Test
  public void testNulls() throws Exception {
    for (Class<?> classToTest : findClassesToTest(loadClassesInPackage(), NULL_TEST_METHOD_NAMES)) {
      try {
        tester.doTestNulls(classToTest, visibility);
      } catch (Throwable e) {
        throw sanityError(classToTest, NULL_TEST_METHOD_NAMES, "nulls test", e);
      }
    }
  }

  /**
   * Tests {@code equals()} and {@code hashCode()} implementations for every top-level class in the
   * package, that explicitly implements {@link Object#equals}. For a class {@code C}:
   *
   * <ul>
   *   <li>The visible constructor or visible static factory method with the most parameters is used
   *       to construct the sample instances. In case of tie, the candidate constructors or
   *       factories are tried one after another until one can be used to construct sample
   *       instances.
   *   <li>For the constructor or static factory method used to construct instances, it's checked
   *       that when equal parameters are passed, the result instance should also be equal; and vice
   *       versa.
   *   <li>Inequality check is not performed against state mutation methods such as {@link
   *       List#add}, or functional update methods such as {@link
   *       com.google.common.base.Joiner#skipNulls}.
   *   <li>If the constructor or factory method used to construct instance takes a parameter that
   *       {@link AbstractPackageSanityTests} doesn't know how to construct, the test will fail.
   *   <li>If there is no visible constructor or visible static factory method declared by {@code
   *       C}, {@code C} is skipped for equality test.
   *   <li>Equality test is not performed on method return values unless the method is a visible
   *       static factory method whose return type is {@code C} or {@code C}'s subtype.
   * </ul>
   *
   * <p>In all cases, if {@code C} needs custom logic for testing {@code equals()}, you can add an
   * explicit {@code testEquals()} test in the corresponding {@code CTest} class, and {@code C} will
   * be excluded from the automated {@code equals} test performed by this method.
   */
  @Test
  public void testEquals() throws Exception {
    for (Class<?> classToTest :
        findClassesToTest(loadClassesInPackage(), EQUALS_TEST_METHOD_NAMES)) {
      if (!classToTest.isEnum() && isEqualsDefined(classToTest)) {
        try {
          tester.doTestEquals(classToTest);
        } catch (Throwable e) {
          throw sanityError(classToTest, EQUALS_TEST_METHOD_NAMES, "equals test", e);
        }
      }
    }
  }

  /**
   * Sets the default value for {@code type}, when dummy value for a parameter of the same type
   * needs to be created in order to invoke a method or constructor. The default value isn't used in
   * testing {@link Object#equals} because more than one sample instances are needed for testing
   * inequality.
   */
  protected final <T> void setDefault(Class<T> type, T value) {
    tester.setDefault(type, value);
  }

  /**
   * Sets two distinct values for {@code type}. These values can be used for both null pointer
   * testing and equals testing.
   *
   * @since 17.0
   */
  protected final <T> void setDistinctValues(Class<T> type, T value1, T value2) {
    tester.setDistinctValues(type, value1, value2);
  }

  /** Specifies that classes that satisfy the given predicate aren't tested for sanity. */
  protected final void ignoreClasses(Predicate<? super Class<?>> condition) {
    this.classFilter = and(this.classFilter, not(condition));
  }

  private static AssertionError sanityError(
      Class<?> cls, List<String> explicitTestNames, String description, Throwable e) {
    String message =
        String.format(
            Locale.ROOT,
            "Error in automated %s of %s\n"
                + "If the class is better tested explicitly, you can add %s() to %sTest",
            description,
            cls,
            explicitTestNames.get(0),
            cls.getName());
    return new AssertionError(message, e);
  }

  /**
   * Finds the classes not ending with a test suffix and not covered by an explicit test whose name
   * is {@code explicitTestNames}.
   */
  @VisibleForTesting
  List<Class<?>> findClassesToTest(
      Iterable<? extends Class<?>> classes, Iterable<String> explicitTestNames) {
    // "a.b.Foo" -> a.b.Foo.class
    TreeMap<String, Class<?>> classMap = Maps.newTreeMap();
    for (Class<?> cls : classes) {
      classMap.put(cls.getName(), cls);
    }
    // Foo.class -> [FooTest.class, FooTests.class, FooTestSuite.class, ...]
    Multimap<Class<?>, Class<?>> testClasses = HashMultimap.create();
    LinkedHashSet<Class<?>> candidateClasses = Sets.newLinkedHashSet();
    for (Class<?> cls : classes) {
      Optional<String> testedClassName = TEST_SUFFIX.chop(cls.getName());
      if (testedClassName.isPresent()) {
        Class<?> testedClass = classMap.get(testedClassName.get());
        if (testedClass != null) {
          testClasses.put(testedClass, cls);
        }
      } else {
        candidateClasses.add(cls);
      }
    }
    List<Class<?>> result = Lists.newArrayList();
    NEXT_CANDIDATE:
    for (Class<?> candidate : Iterables.filter(candidateClasses, classFilter)) {
      for (Class<?> testClass : testClasses.get(candidate)) {
        if (hasTest(testClass, explicitTestNames)) {
          // covered by explicit test
          continue NEXT_CANDIDATE;
        }
      }
      result.add(candidate);
    }
    return result;
  }

  private List<Class<?>> loadClassesInPackage() throws IOException {
    List<Class<?>> classes = Lists.newArrayList();
    String packageName = getClass().getPackage().getName();
    for (ClassPath.ClassInfo classInfo :
        ClassPath.from(getClass().getClassLoader()).getTopLevelClasses(packageName)) {
      Class<?> cls;
      try {
        cls = classInfo.load();
      } catch (NoClassDefFoundError e) {
        // In case there were linking problems, this is probably not a class we care to test anyway.
        logger.log(Level.SEVERE, "Cannot load class " + classInfo + ", skipping...", e);
        continue;
      }
      if (!cls.isInterface()) {
        classes.add(cls);
      }
    }
    return classes;
  }

  private static boolean hasTest(Class<?> testClass, Iterable<String> testNames) {
    for (String testName : testNames) {
      try {
        testClass.getMethod(testName);
        return true;
      } catch (NoSuchMethodException e) {
        continue;
      }
    }
    return false;
  }

  private static boolean isEqualsDefined(Class<?> cls) {
    try {
      return !cls.getDeclaredMethod("equals", Object.class).isSynthetic();
    } catch (NoSuchMethodException e) {
      return false;
    }
  }

  abstract static class Chopper {

    final Chopper or(Chopper you) {
      Chopper i = this;
      return new Chopper() {
        @Override
        Optional<String> chop(String str) {
          return i.chop(str).or(you.chop(str));
        }
      };
    }

    abstract Optional<String> chop(String str);

    static Chopper suffix(String suffix) {
      return new Chopper() {
        @Override
        Optional<String> chop(String str) {
          if (str.endsWith(suffix)) {
            return Optional.of(str.substring(0, str.length() - suffix.length()));
          } else {
            return Optional.absent();
          }
        }
      };
    }
  }
}
