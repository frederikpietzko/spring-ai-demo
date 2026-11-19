# adjuster-skills Specification

## Purpose

Defines the adjuster agent's skills: short `SKILL.md` documents (`kaiju-classification`, `damage-estimation`) of which the model sees only the name and description up front and loads the full text on demand through the `Skill` tool.

## ADDED Requirements

### Requirement: Skills are discovered from the classpath
The backend SHALL load every `SKILL.md` under `skills/` on the classpath as one skill, named by its `name` frontmatter and described by its `description` frontmatter. The skills `kaiju-classification` and `damage-estimation` SHALL exist.

#### Scenario: Both skills are found
- **WHEN** the skill tool is built from the classpath resource `skills`
- **THEN** its tool definition is named `Skill` and its description contains the names and descriptions of `kaiju-classification` and `damage-estimation`

#### Scenario: An unknown skill is reported
- **WHEN** the skill tool is called with the command `no-such-skill`
- **THEN** the result says the skill was not found and names it

### Requirement: Only names and descriptions are offered up front
The prompt of an adjuster request SHALL carry the name and description of every skill, in the definition of the `Skill` tool, and SHALL NOT carry the body of any skill, neither in a message nor in a tool definition.

#### Scenario: Descriptions are up front, bodies are not
- **WHEN** an adjuster request is answered
- **THEN** the definition of the `Skill` tool in the first prompt received by the model contains both skill names and both descriptions, and neither that definition nor any message of that prompt contains a sentence taken from a skill body

### Requirement: A skill loads on demand
When the model calls `Skill` with the name of a skill, the tool result SHALL contain the body of that skill (without its frontmatter); the library may prefix it with the skill's base directory. The model SHALL receive the result in the next prompt of the same request.

#### Scenario: The model loads a skill
- **WHEN** the model requests `Skill` with the command `kaiju-classification` and then replies "Class 3.", and a client posts an adjuster request
- **THEN** the tool response in the second prompt received by the model contains a sentence from the body of `kaiju-classification`, and the events are text delta "Class 3." and done
