# SEBI Disclosure Processor

A Spring Boot CLI application that processes 49 SEBI regulatory disclosure PDFs, classifies board director changes, and extracts structured entity data into a JSON report. Prompt engineering was done in Python/Jupyter notebooks before the prompts were frozen and embedded into the Java pipeline.

---

## Table of Contents

- [How to Run](#how-to-run)
- [Architectural Approach](#architectural-approach)
- [The Three Most Important Tradeoffs](#the-three-most-important-tradeoffs)
- [Edge Cases Handled and Not Handled](#edge-cases-handled-and-not-handled)
- [AI Services and External Libraries](#ai-services-and-external-libraries)
- [Evaluation Note](#evaluation-note)

---

## How to Run

### Prerequisites

- Java 21+
- Maven 3.8+
- At least one LLM API key (Groq is the default and cheapest for testing)

### 1. Clone and enter the repo

```bash
git clone <repo-url>
cd sapiensu_take_home_dataset/Sapiensu
```

### 2. Set your API key

```bash
# Linux / macOS
export GROQ_API_KEY=your_key_here

# Windows (PowerShell)
$env:GROQ_API_KEY="your_key_here"
```

To use Anthropic or Gemini instead, set the corresponding key and update `llm.provider` in `application.yml`:

```bash
export ANTHROPIC_API_KEY=your_key_here   # then set llm.provider: anthropic
export GOOGLE_API_KEY=your_key_here      # then set llm.provider: gemini
```

### 3. Place PDFs in the input directory

```
Sapiensu/pdfs/    ← put all 49 PDFs here
```

### 4. Configure the input path

Open `src/main/resources/application.yml` and set the absolute path to your `pdfs/` folder:

```yaml
processing:
  input-dir: /absolute/path/to/your/pdfs
  output-dir: ./output
  output-filename: results.json
  concurrency: 1
  text-truncation-chars: 4000
```

> **Windows note:** Use forward slashes even on Windows (`C:/Users/you/path/pdfs`).
> Do not use `./pdfs` as a relative path when running from IntelliJ — the JVM working
> directory may not resolve it correctly, producing an `Input directory not found` error.
> Fix: go to Run → Edit Configurations → set Working Directory to `$MODULE_WORKING_DIR$`.

### 5. Build and run

```bash
mvn clean package -DskipTests
mvn spring-boot:run
```

Or run `SebiProcessorApplication` directly from IntelliJ after setting the working directory above.

### 6. Output

Results are written to `./output/`:

| File | Description |
|---|---|
| `results.json` | Structured extraction results for all director changes |
| `processing_report.txt` | Human-readable per-document summary |

### Running the Python QA notebooks (optional)

```bash
pip install -r requirements.txt
jupyter notebook
```

Open `notebooks/01_prompt_development.ipynb` to see how prompts were iterated, and `notebooks/02_output_qa.ipynb` to audit the pipeline output against the schema.

---

## Architectural Approach

The system is a Spring Boot `CommandLineRunner` that processes a directory of PDFs through a five-stage sequential pipeline. `PipelineRunner` discovers all `.pdf` files, passes them to `ProcessingOrchestrator`, and writes the final output. Each PDF flows through five services in a fixed order:

**PdfIngestionService** uses Apache PDFBox to extract raw text. Scanned image PDFs that produce no text are marked `FAILED` and short-circuit the rest of the pipeline immediately.

**TextNormalisationService** cleans whitespace anomalies common in BSE/NSE filings — non-breaking spaces, Windows line endings, control characters — then splits the text into overlapping 10,000-character chunks with a 500-character overlap. Chunking is newline-aware to avoid splitting mid-sentence.

**ClassificationService** decides whether a document contains a board director change. Before touching the LLM, each chunk passes through a **RuleEngine** (pure regex, zero API cost) that applies three sequential gates: a minimum-presence check for director-related keywords; a strong-signal check against patterns like `resign(ed|ation) from the board` and `DIN:\d{8}`; and an exclusion-signal check for CFO and Company Secretary patterns. Chunks that fail the first two gates are skipped entirely. Chunks that hit an exclusion signal alongside a strong signal are routed to a stricter `classify_cautious.txt` prompt designed specifically for bundled disclosures. The classifier stops at the first chunk that returns `true`, so most documents require only one LLM call.

**EntityExtractionService** runs only on documents the classifier confirms as director changes. It sends the full normalised text to the extraction prompt and receives a JSON array of all director changes in the document. Post-processing drops any extraction missing a `director_name` or `change_type`. `source_filename` is stamped onto each result in Java, not trusted from the LLM.

**OutputAggregatorService** collects all `DisclosureRecord` objects and flatmaps extraction arrays into a single `ProcessingOutput` with a `summary` block and a flat `extractions` list.

The three LLM providers (Groq, Anthropic, Gemini) are wired via `@ConditionalOnProperty` — only the active provider's `LlmClient` bean is instantiated. Switching is a one-line change in `application.yml`. All prompts live in `src/main/resources/prompts/` and are loaded at startup via `@PostConstruct`, not hardcoded in Java. Prompt versions were iterated in `01_prompt_development.ipynb` using Gemini 2.0 Flash against 15 representative PDFs before the final versions were frozen and copied into the Java resources directory.

---

## The Three Most Important Tradeoffs

### 1. Rule engine pre-filter before every LLM call

**What I did:** A regex rule engine gates every chunk before any LLM call. A chunk must pass two positive gates (keyword presence, strong structural pattern) to reach the LLM at all. A third gate detects mixed-signal chunks and routes them to a stricter prompt.

**Why:** LLM API calls are the bottleneck in both latency and cost, especially at free-tier rate limits. The rule engine eliminates the majority of chunks — financial results sections, trading window notices, AGM boilerplate — without spending a token. It also reduces false positives: the word "director" appears in many non-qualifying contexts (company history, committee names, hyperlink labels).

**What I would do with more time:** The rule engine currently gates only classification. Extraction is run on the full normalised text. With more time I would pass only the rule-matched chunks to the extraction prompt too, reducing token usage and noise from surrounding irrelevant content. I would also add a scoring-based threshold rather than a binary PASS/SKIP decision, to allow borderline chunks to be batched and reviewed rather than silently dropped.

### 2. Two-prompt classification (standard + cautious)

**What I did:** When the rule engine detects a strong director signal and an exclusion signal in the same chunk, it routes to `classify_cautious.txt`, a stricter prompt whose entire focus is the distinction between board directors and functional-role executives (CFO, CS, divisional directors).

**Why:** The standard prompt struggled with bundled disclosures where a CFO change and a director change appear in the same paragraph. A single prompt cannot give equal prominence to both "here is what counts" and "here is what does not count." Separating them into two purpose-built prompts lets each one be precise.

**What I would do with more time:** Convert both prompts to few-shot format with labelled examples drawn from the actual dataset. Instruction-following alone has a non-trivial failure rate on boundary cases; concrete positive and negative examples are significantly more reliable and require no model fine-tuning.

### 3. Concurrency set to 1

**What I did:** `concurrency: 1` in `application.yml`. The `ProcessingOrchestrator` uses a `ForkJoinPool` with a configurable parallelism level, but it is set to sequential for the submission.

**Why:** Groq's free tier enforces strict request-per-minute limits. At concurrency > 1, the pipeline would hit 429 errors immediately and spend more time in retry backoff than it saves in parallelism. Sequential processing with linear backoff on retries is predictable and reliable at this scale.

**What I would do with more time:** Implement a token-bucket rate limiter so that concurrency can be increased up to the API's actual request budget rather than defaulting to 1. For 50,000 documents, sequential processing is not viable — the right architecture is an async queue (e.g. Spring Batch or a message queue like SQS) where workers consume jobs at a rate the API can sustain, with dead-letter handling for failed documents.

---

## Edge Cases Handled and Not Handled

### Handled

**CFO and Company Secretary misclassification.** The classification prompt explicitly lists CFO, CS, and KMP roles as non-qualifying, with examples. The cautious prompt is invoked for mixed-signal chunks. The rule engine's exclusion patterns catch the most common cases before any LLM call.

**Multi-change documents.** The extraction prompt explicitly instructs the model to extract ALL director changes and return them as a JSON array. `OutputAggregatorService` flatmaps extraction arrays, so a single PDF can contribute multiple rows to `results.json`.

**Re-appointment language.** Both the rule engine (pattern: `re-?appoint(ed|ment)? as ... director`) and the extraction prompt (`re-appointment = "appointment"`) handle this mapping explicitly.

**Cessation language.** `cessation` maps to `resignation` in extraction unless removal is explicit. The rule engine includes `cessation of directorship` and `cessation of office ... director` as strong signal patterns.

**DIN number as a hard classification signal.** A `DIN: \d{8}` regex is a near-certain indicator of a board director change, since Director Identification Numbers are exclusive to board-level appointees in India. The rule engine treats this as a strong signal regardless of surrounding context.

**Postal ballot and regularisation language.** Patterns for `appointment and regularisation of ... director` are included in the rule engine's strong-signal list, covering AGM-style filings where language differs from routine appointment disclosures.

**Date format normalisation.** The extraction prompt explicitly instructs the model to convert `1st March 2024` and `March 1, 2024` to `YYYY-MM-DD`. `ExtractionResult` uses `LocalDate` with Jackson JSR-310, so malformed dates throw a deserialisation exception rather than silently producing garbage.

**Scanned / image PDFs.** PDFBox returns blank or near-blank text for scanned documents. `PdfIngestionService` catches the blank-text case, marks the record `FAILED`, and adds the filename to `documents_that_failed_processing` in the summary.

**JSON fence stripping.** Despite prompting for raw JSON, LLMs occasionally wrap responses in markdown code fences. Both `ClassificationService` and `EntityExtractionService` strip ` ```json ` and ` ``` ` fences and extract the JSON object or array by bracket position as a secondary fallback.

**DIN number bleeding into director name.** The extraction prompt explicitly prohibits including the DIN in `director_name` — e.g. extract `Priya Sharma`, not `Priya Sharma (DIN: 12345678)`.

### Not Handled

**Scanned PDFs requiring OCR.** PDFBox cannot extract text from image-only PDFs. These will always be marked `FAILED`. A production system would need a fallback OCR pass (e.g. Tesseract, AWS Textract, or Google Document AI).

**Disclosures referenced by hyperlink only.** Some SEBI filings contain a URL to the actual disclosure with no inline text. The system has no mechanism to follow links and fetch the referenced document. The extraction prompt flags this case as non-extractable but takes no action.

**Chunked extraction boundary splits.** If a director's name appears in one chunk and their effective date appears in a later chunk that the rule engine skips, the extraction from either chunk alone will have null fields. The current design sends the full normalised text to extraction (not just matched chunks), which mitigates this, but `text-truncation-chars: 4000` limits very long documents.

**Confidence score calibration.** `extraction_confidence` is self-reported by the LLM against qualitative instructions. It is useful for prioritising manual review but is not a calibrated probability and should not be treated as one.

**Ambiguous cessation vs. removal.** The system defaults cessation to `resignation` unless removal is explicitly stated. This may mislabel director removals following shareholder votes or legal proceedings.

**Non-English content.** Some BSE/NSE filings include Hindi sections. The LLM generally handles these, but this is untested and not guaranteed to produce correct structured output.

**Multiple directors with identical names.** No deduplication logic exists beyond the `director_name` null-check. If the same name appears in different contexts (historical reference and current change), the extraction may produce duplicate records.

---

## AI Services and External Libraries

### LLM Providers

**Groq — LLaMA 3.3-70B Versatile (default, `llm.provider: groq`)**
Used as the primary LLM because Groq's free tier is fast and the rate limits are sufficient for a 49-document batch. LLaMA 3.3-70B follows structured JSON instructions reliably and has adequate knowledge of Indian regulatory terminology (DIN, BSE/NSE, SEBI Regulation 30).

**Anthropic Claude claude-opus-4-5 (optional, `llm.provider: anthropic`)**
Available as a higher-quality alternative. Claude is more conservative with JSON format compliance and less likely to add prose around the JSON response, reducing fence-stripping edge cases. Higher cost than Groq at scale.

**Google Gemini 2.0 Flash (optional, `llm.provider: gemini`)**
Used in the Jupyter notebooks for prompt development because its free API tier requires no credit card, making rapid iteration cheap. The `responseMimeType: application/json` Gemini parameter enforces JSON output at the API level. Note: `max-tokens: 256` in the current config is too low for complex disclosures and should be raised to at least 1024 if using Gemini in production.

### Java Libraries

**Apache PDFBox 3.0.2**
The industry-standard Java PDF text extractor. Handles the majority of BSE/NSE filing formats. Chosen over iText because PDFBox is Apache-licensed with no AGPL restrictions. Its hard limitation is image-only PDFs, which require separate OCR tooling.

**Spring Boot 3.3 / spring-web (no embedded Tomcat)**
Spring Boot provides `CommandLineRunner` (CLI entrypoint), `@ConfigurationProperties` (type-safe YAML binding), and `@ConditionalOnProperty` (provider switching). `spring-web` is included without `spring-boot-starter-web` specifically to avoid starting an embedded Tomcat — this is a batch processor, not a web application.

**Jackson Databind + jackson-datatype-jsr310**
Jackson deserialises LLM JSON responses into typed model objects (`ExtractionResult`, `Confidence` enum, `ChangeType` enum) and serialises the final output file. The JSR-310 module is required for `LocalDate` serialisation, which also serves as a date-format validator at deserialisation time.

**Lombok**
Eliminates boilerplate (`@Data`, `@Builder`, `@Slf4j`, `@RequiredArgsConstructor`) on model and service classes. Excluded from the final JAR via the Maven plugin configuration.

### Python Libraries (Notebooks only)

**pdfplumber** — PDF text extraction for the prompt development notebook.

**google-generativeai** — Gemini Python SDK used during prompt iteration.

**pandas** — DataFrame-based schema validation, null field analysis, and confidence distribution reporting in `02_output_qa.ipynb`.

---

## Evaluation Note

The classification and extraction prompts were developed iteratively against a representative sample of 15 PDFs spanning four categories — obvious director changes, obvious non-changes, CFO-only disclosures, and multi-change documents — using the process documented in `01_prompt_development.ipynb`. The final prompts correctly handled all 15 test cases. The full pipeline was run against all 49 PDFs and the output was validated by `02_output_qa.ipynb`, which checks schema correctness, date format compliance, null field rates, confidence distributions, and spots multi-extraction documents. That said, I did not have a ground-truth labelled dataset, so there is no precision or recall figure to report — the QA is structural and spot-check-based, not metric-based. My honest assessment is that classification accuracy is high on clear-cut cases (the rule engine alone filters most irrelevant chunks before the LLM is involved, and the LLM handles the remainder reliably on well-formed documents), but I have lower confidence on boundary cases: documents where a CFO change and a director change appear in the same sentence, documents using indirect language like "regularisation of appointment," and filings where the effective date is expressed as a board meeting date rather than an explicit handover date. The extraction is likely accurate on `director_name` and `change_type` for confirmed director-change documents, moderate on `effective_date` (date normalisation is instructed but not always followed perfectly by the model), and low on `stock_ticker` (most SEBI filings do not include the ticker symbol inline, so the majority of ticker fields will legitimately be null). I would trust this output as a first-pass dataset for human review, but not as a production-quality source of truth without a labelled evaluation set.