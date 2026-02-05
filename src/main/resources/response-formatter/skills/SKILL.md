---
name: response-formatter
description: Responsible for converting unstructured text into standard Feishu Rich Text JSON.
---

# Response Formatter Skill

This skill is a pure function that formats the previous agent's Markdown response into a structured JSON object for the Feishu messaging platform.

## Instructions

Your input is the last message from another Assistant. Your output must be a valid JSON object matching the `StructuredOutputResult` schema.

1.  **Content Extraction**: Extract the core body of the previous message.
2.  **Structure Assembly (FeishuPostContent)**:
    -   `title`: Generate a short, punchy title based on content.
    -   `content`: Split by paragraphs.
        -   Text segments: `tag: "text"`.
        -   Hyperlinks: `tag: "a"`, `text: "Name"`, `href: "url"`.
        -   **Image Preservation (CRITICAL)**: Convert Markdown images `![desc](url)` to `tag: "img"`, `url: "[url]"`.
3.  **Metadata**:
    -   `thoughts`: Briefly explain extraction process (e.g., "Extracted recipe steps and preserved 2 images").
    -   `action`: Fixed value "SEND_FEISHU".
    -   `tags`: Extract 2-4 content-related tags.

**CONSTRAINT**: Output RAW JSON only. No markdown formatting blocks (like ```json ... ```). No explanatory text.

## Examples

**Input (Markdown)**:
"Here is the recipe for scrambled eggs.\n\n1. Beat eggs.\n2. Fry them.\n![Eggs](http://img.com/1.jpg)"

**Output (JSON)**:
{
  "title": "Scrambled Eggs Recipe",
  "content": [
    {"tag": "text", "text": "Here is the recipe for scrambled eggs.\n\n1. Beat eggs.\n2. Fry them."},
    {"tag": "img", "url": "http://img.com/1.jpg"}
  ],
  "action": "SEND_FEISHU",
  "tags": ["Recipe", "Eggs"],
  "thoughts": "Formatted recipe text and preserved one image."
}
