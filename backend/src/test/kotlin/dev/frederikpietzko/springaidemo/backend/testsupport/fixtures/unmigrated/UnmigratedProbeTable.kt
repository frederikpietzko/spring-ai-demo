package dev.frederikpietzko.springaidemo.backend.testsupport.fixtures.unmigrated

import org.jetbrains.exposed.v1.core.Table

/** Fixture for the schema agreement check: a well-formed table definition that no migration creates. */
object UnmigratedProbeTable : Table("unmigrated_probe")
