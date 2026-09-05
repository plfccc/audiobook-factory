ALTER TABLE book_version
    ADD CONSTRAINT uq_book_version_source_sha256 UNIQUE (source_file_sha256);
