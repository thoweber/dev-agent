# dev-agent Global Writing & Documentation Standards

> **Master Reference:** Applicable globally across all documentation (`/docs`), agent responses, rule files, skills, and pull request descriptions.

## 1. Concise GitHub-Flavored Markdown (GFM)
* **No Fluff or Filler:** Write short, punchy sentences focused strictly on actionable information. Omit introductory fluff ("In this section we will discuss...") and repetitive summaries.
* **Structured Scannability:** Present multi-dimensional or comparative data using GFM tables. Use bullet points for steps and requirements.
* **Fenced Code Blocks:** Always specify language identifiers (````java`, ````bash`, ````json`, ````yaml`) for syntax highlighting.

## 2. Active Voice & Direct Tone
* **Action-Oriented Verbs:** Use active voice and imperative mood ("Run the verification loop", "Validate canonical path", "Inject the SPI interface") rather than passive phrasing.
* **Technical Precision:** Use exact domain terminology (e.g., *Path Jail*, *Two-Axis SPI*, *Lexical BM25 Inverted Index*, *Non-Locking State Socket*).

## 3. Mandatory Clickable File Links
* **`file://` Scheme Required:** Every mention of a file path, script, configuration file, or code symbol MUST be formatted as a clickable GFM link using the `file://` URI scheme:
  * **File:** `[AGENTS.md](file:///home/developer/projects/dev-agent/AGENTS.md)`
  * **Line Range:** `[pom.xml:L40-L60](file:///home/developer/projects/dev-agent/pom.xml#L40-L60)`
* **Readable Link Text:** Use file basenames or descriptive symbol names for link text (never unformatted raw paths).

## 4. Strategic GitHub-Style Alerts
Use GFM alert callouts to emphasize critical operational context:
* `> [!NOTE]` — Helpful background context or design rationale.
* `> [!TIP]` — Efficiency hints, fast feedback loop shortcuts, or performance optimizations.
* `> [!IMPORTANT]` — Essential prerequisites or mandatory execution requirements.
* `> [!WARNING]` — Breaking changes, contract deprecations, or configuration caveats.
* `> [!CAUTION]` — High-risk actions, data loss risks, or security boundary violations.

## 5. Clean Mermaid.js Diagrams
* **Valid Syntax:** Use inline fenced ````mermaid```` code blocks (`graph TD`, `flowchart LR`, `sequenceDiagram`).
* **Quote Node Labels:** Quote labels containing special characters, parentheses, or brackets (`node["Label (Extra)"]`) to prevent rendering parse failures.

## 6. Grounded Evidence & Zero Placeholder Policy
* **Authoritative Inspection:** Never guess file paths, variable names, or schemas. Inspect authoritative files before writing technical descriptions.
* **No Dummy Placeholders:** Never use placeholder text (`TODO`, `TBD`, `foo/bar`). Write complete, production-ready documentation.

## 7. Emoji Usage Restriction
* **No Emojis in Text:** Do NOT use emojis in agent responses, technical documentation (`/docs`), commit messages, PR descriptions, or rule files.
* **Root README Exception:** Emojis are permitted ONLY in the repository root `README.md`, where they may be used sparingly to highlight major section headers.
