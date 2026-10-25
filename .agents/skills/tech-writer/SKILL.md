---
name: tech-writer
description: Authors Architecture Decision Records (ADRs), user walkthroughs, CLI manual pages, and concise markdown documentation for dev-agent.
---

# Technical Writer Skill (`tech-writer`)

This skill governs documentation quality, Architecture Decision Records (ADRs), and user-facing CLI documentation across **dev-agent**.

---

## 1. Documentation Principles

* Adhere strictly to `.agents/rules/04-writing-standards.md`.
* Use concise, actionable GitHub-Flavored Markdown.
* Format all file and symbol references as clickable links using the `file://` scheme.
* Use Mermaid diagrams for architecture, sequence flows, and state machines.
* No emojis in documentation or code (permitted only sparingly in root `README.md`).

---

## 2. ADR Lifecycle

* New architectural decisions must be recorded under `docs/adr/` using `.agents/templates/adr-template.md`.
* Maintain the ADR index in `docs/adr/index.md`.
