package com.audiobookfactory.control.book;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/books")
public class BookController {

    private final BookService bookService;

    public BookController(BookService bookService) {
        this.bookService = bookService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BookImportResult> importBook(
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestPart(value = "epub", required = false) MultipartFile epub) {
        MultipartFile upload = file != null ? file : epub;
        EpubImportService.ImportOutcome outcome = bookService.importBook(upload);
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(outcome.result());
    }

    @GetMapping
    public List<BookService.BookSummary> listBooks() {
        return bookService.listBooks();
    }

    @GetMapping("/{bookId}")
    public BookService.BookDetail getBook(@PathVariable long bookId) {
        return bookService.getBook(bookId);
    }

    @GetMapping("/{bookId}/chapters")
    public List<BookService.ChapterView> listChapters(@PathVariable long bookId) {
        return bookService.listChapters(bookId);
    }

    @GetMapping("/{bookId}/chapters/{chapterId}/segments")
    public List<BookService.SegmentView> listSegments(
            @PathVariable long bookId, @PathVariable String chapterId) {
        return bookService.listSegments(bookId, chapterId);
    }
}
