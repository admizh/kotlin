// FILE: JavaClass.java

public class JavaClass {
    public static void foo(ArrayList list) {}
    public static void bar(Class clz) {}
}

// FILE: test.kt

class Some

fun test(list: ArrayList<Some>) {
    JavaClass.foo(list)
    JavaClass.bar(Some::class.java)
}