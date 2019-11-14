open class Protected {
    protected fun bar() {}

    fun baz() {
        bar()
        Nested().foo()
    }

    inner class Inner {
        fun foo() {
            bar()
        }
    }

    protected open class Nested {
        fun foo() {
            bar()
        }

        protected fun bar() {}
    }
}

class Derived : Protected() {
    fun foo() {
        bar()
        Nested().foo()
        Nested().<!INAPPLICABLE_CANDIDATE!>bar<!>() // hidden
    }

    class NestedDerived : Nested() {
        fun use() {
            bar()
        }
    }
}

fun test() {
    Protected().baz()
    Protected().Inner()

    Protected().<!INAPPLICABLE_CANDIDATE!>bar<!>() // hidden
    Protected.<!INAPPLICABLE_CANDIDATE!>Nested<!>() // hidden
}
