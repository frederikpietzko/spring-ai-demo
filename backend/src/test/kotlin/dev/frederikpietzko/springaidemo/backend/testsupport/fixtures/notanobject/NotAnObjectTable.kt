package dev.frederikpietzko.springaidemo.backend.testsupport.fixtures.notanobject

import org.jetbrains.exposed.v1.core.Table

/** Fixture for the table discovery guard: a table definition that is a plain class instead of a singleton object. */
class NotAnObjectTable : Table("not_an_object")
