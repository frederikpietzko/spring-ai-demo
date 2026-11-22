package dev.frederikpietzko.springai.kotlin

/**
 * Marks the receivers of the Spring AI DSL, so a nested block does not resolve the functions of an outer one (no
 * `tools(..)` inside `advisors { }`). Also usable on a function type's receiver for Spring's own builder types.
 */
@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
annotation class SpringAiDsl
