package com.audiobookfactory.control.book;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextSegmenterTest {

    private final TextSegmenter segmenter = new TextSegmenter();

    @Test
    void neverSplitsInsideASentence() {
        List<SegmentDraft> segments = segmenter.segment(
                "第一句内容。第二句内容！第三句内容？",
                new SegmentationPolicy(2, 8, 12, "v1"));

        assertThat(segments).isNotEmpty();
        assertThat(segments).extracting(SegmentDraft::text)
                .containsExactly("第一句内容。", "第二句内容！", "第三句内容？");
        assertThat(segments).extracting(SegmentDraft::index)
                .containsExactly(1, 2, 3);
    }

    @Test
    void triesParagraphSentenceCommaAndDunhaoBoundariesBeforeHardSplitting() {
        List<SegmentDraft> segments = segmenter.segment(
                "甲乙，丙丁、戊己。\n庚辛壬癸子丑寅卯辰巳午未申酉戌亥",
                new SegmentationPolicy(2, 5, 10, "v1"));

        assertThat(segments).extracting(SegmentDraft::text)
                .containsExactly("甲乙，", "丙丁、", "戊己。", "庚辛壬癸子", "丑寅卯辰巳", "午未申酉戌亥");
    }

    @Test
    void splitsOnlyAnOverlongSingleSentenceAtSafeCharacterBoundaries() {
        String longSentence = "这是一个没有句末标点的超长句子";

        List<SegmentDraft> segments = segmenter.segment(
                longSentence,
                new SegmentationPolicy(2, 8, 10, "v1"));

        assertThat(segments).isNotEmpty();
        assertThat(segments).allSatisfy(segment -> assertThat(segment.text().length()).isLessThanOrEqualTo(10));
        assertThat(segments).extracting(SegmentDraft::text)
                .containsExactly("这是一个没有句末", "标点的超长句子");
        assertThat(String.join("", segments.stream().map(SegmentDraft::text).toList()))
                .isEqualTo(longSentence);
    }

    @Test
    void mergesShortTrailingSegmentWithinTheSameChapter() {
        List<SegmentDraft> segments = segmenter.segment(
                "一二三四五六。七八九。",
                new SegmentationPolicy(5, 8, 12, "v1"));

        assertThat(segments).extracting(SegmentDraft::text)
                .containsExactly("一二三四五六。七八九。");
    }

    @Test
    void doesNotMergeShortTrailingSegmentWhenTheMergeWouldExceedMaxChars() {
        List<SegmentDraft> segments = segmenter.segment(
                "一二三四五六七八九。十十一。",
                new SegmentationPolicy(5, 8, 12, "v1"));

        assertThat(segments).extracting(SegmentDraft::text)
                .containsExactly("一二三四五六七八九。", "十十一。");
    }

    @Test
    void preservesSpacesAfterEnglishPunctuationAcrossSegmentBoundaries() {
        String chapterText = "Hello world, this is a sentence.";

        List<SegmentDraft> segments = segmenter.segment(
                chapterText,
                new SegmentationPolicy(2, 12, 20, "v1"));

        assertThat(segments).extracting(SegmentDraft::text)
                .containsExactly("Hello world,", " this is a sentence.");
        assertThat(String.join("", segments.stream().map(SegmentDraft::text).toList()))
                .isEqualTo(chapterText);
    }
}
