from app.indexing.chunker import chunk_tokens, split_pages, tokenize


def test_split_pages_by_form_feed() -> None:
    content = "page1 text\fpage2 text"
    pages = split_pages(content)
    assert len(pages) == 2
    assert pages[0] == "page1 text"
    assert pages[1] == "page2 text"


def test_chunk_tokens_basic() -> None:
    tokens = tokenize("a b c d e f g h i j")
    chunks = chunk_tokens(tokens, size=4, overlap=1)
    assert len(chunks) == 3
    assert chunks[0][2] == ["a", "b", "c", "d"]
    assert chunks[1][2] == ["d", "e", "f", "g"]
    assert chunks[2][2] == ["g", "h", "i", "j"]

