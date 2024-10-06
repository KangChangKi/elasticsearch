/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.analysis.custom;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.ko.KoreanAnalyzer;
import org.apache.lucene.analysis.ko.KoreanTokenizer;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.index.IndexVersions;
import org.elasticsearch.index.analysis.AnalysisTestsHelper;
import org.elasticsearch.index.analysis.IndexAnalyzers;
import org.elasticsearch.index.analysis.NamedAnalyzer;
import org.elasticsearch.index.analysis.TokenFilterFactory;
import org.elasticsearch.index.analysis.TokenizerFactory;
import org.elasticsearch.test.ESTokenStreamTestCase;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.apache.lucene.tests.analysis.BaseTokenStreamTestCase.assertTokenStreamContents;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;

public class MyCustomAnalysisTests extends ESTokenStreamTestCase {
    public void testDefaultsCustomAnalysis() throws IOException {
        TestAnalysis analysis = createTestAnalysis(Settings.EMPTY);

        TokenizerFactory tokenizerFactory = analysis.tokenizer.get("custom_tokenizer");
        assertThat(tokenizerFactory, instanceOf(CustomTokenizerFactory.class));

        TokenFilterFactory filterFactory = analysis.tokenFilter.get("custom_part_of_speech");
        assertThat(filterFactory, instanceOf(CustomPartOfSpeechStopFilterFactory.class));

        filterFactory = analysis.tokenFilter.get("custom_readingform");
        assertThat(filterFactory, instanceOf(CustomReadingFormFilterFactory.class));

        filterFactory = analysis.tokenFilter.get("custom_number");
        assertThat(filterFactory, instanceOf(CustomNumberFilterFactory.class));

        IndexAnalyzers indexAnalyzers = analysis.indexAnalyzers;
        NamedAnalyzer analyzer = indexAnalyzers.get("custom");
        assertThat(analyzer.analyzer(), instanceOf(KoreanAnalyzer.class));
    }

    public void testCustomAnalyzer1() throws Exception {
//        Settings settings = Settings.builder()
//            .put("index.analysis.analyzer.my_analyzer.type", "custom_analyzer")
//            .put("index.analysis.analyzer.my_analyzer.stoptags", "NR, SP")
//            .put("index.analysis.analyzer.my_analyzer.decompound_mode", "mixed")
//            .build();
//        TestAnalysis analysis = createTestAnalysis(settings);
//        Analyzer analyzer = analysis.indexAnalyzers.get("my_analyzer");

        TestAnalysis analysis = createTestAnalysis(Settings.EMPTY);
        Analyzer analyzer = analysis.indexAnalyzers.get("custom_analyzer");
        try (TokenStream stream = analyzer.tokenStream("", "가늠표")) {
            assertTokenStreamContents(stream, new String[] { "가늠", "표" });
        }
    }

    public void testCustomAnalyzer2() throws Exception {
        Settings settings = Settings.builder()
            .put("index.analysis.analyzer.my_analyzer2.tokenizer", "custom_tokenizer")
            .put("index.analysis.analyzer.my_analyzer2.type", "custom_analyzer")
            .build();
        TestAnalysis analysis = createTestAnalysis(settings);
        Analyzer analyzer = analysis.indexAnalyzers.get("my_analyzer2");
        try (TokenStream stream = analyzer.tokenStream("", "가늠표")) {
            assertTokenStreamContents(stream, new String[] { "가늠", "표" });
        }
    }

    public void testCustomAnalyzerUserDict() throws Exception {
        Settings settings = Settings.builder()
            .put("index.analysis.analyzer.my_analyzer.type", "custom_analyzer")
            .putList("index.analysis.analyzer.my_analyzer.user_dictionary_rules", "c++", "C쁠쁠", "세종", "세종시 세종 시")
            .build();
        TestAnalysis analysis = createTestAnalysis(settings);
        Analyzer analyzer = analysis.indexAnalyzers.get("my_analyzer");
        try (TokenStream stream = analyzer.tokenStream("", "세종시")) {
            assertTokenStreamContents(stream, new String[] { "세종", "시" });
        }

        try (TokenStream stream = analyzer.tokenStream("", "c++world")) {
            assertTokenStreamContents(stream, new String[] { "c++", "world" });
        }
    }

    public void testCustomAnalyzerUserDictPath() throws Exception {
        Settings settings = Settings.builder()
            .put("index.analysis.analyzer.my_analyzer.type", "custom_analyzer")
            .put("index.analysis.analyzer.my_analyzer.user_dictionary", "user_dict.txt")
            .build();
        TestAnalysis analysis = createTestAnalysis(settings);
        Analyzer analyzer = analysis.indexAnalyzers.get("my_analyzer");
        try (TokenStream stream = analyzer.tokenStream("", "세종시")) {
            assertTokenStreamContents(stream, new String[] { "세종", "시" });
        }

        try (TokenStream stream = analyzer.tokenStream("", "c++world")) {
            assertTokenStreamContents(stream, new String[] { "c++", "world" });
        }
    }

    public void testCustomAnalyzerInvalidUserDictOption() throws Exception {
        Settings settings = Settings.builder()
            .put("index.analysis.analyzer.my_analyzer.type", "custom_analyzer")
            .put("index.analysis.analyzer.my_analyzer.user_dictionary", "user_dict.txt")
            .putList("index.analysis.analyzer.my_analyzer.user_dictionary_rules", "c++", "C쁠쁠", "세종", "세종시 세종 시")
            .build();
        IllegalArgumentException exc = expectThrows(IllegalArgumentException.class, () -> createTestAnalysis(settings));
        assertThat(
            exc.getMessage(),
            containsString("It is not allowed to use [user_dictionary] in conjunction " + "with [user_dictionary_rules]")
        );
    }

    public void testCustomAnalyzerDuplicateUserDictRule() throws Exception {
        Settings settings = Settings.builder()
            .put("index.analysis.analyzer.my_analyzer.type", "custom_analyzer")
            .put(IndexMetadata.SETTING_VERSION_CREATED, IndexVersions.NORI_DUPLICATES)
            .putList("index.analysis.analyzer.my_analyzer.user_dictionary_rules", "c++", "C쁠쁠", "세종", "세종", "세종시 세종 시")
            .build();

        final IllegalArgumentException exc = expectThrows(IllegalArgumentException.class, () -> createTestAnalysis(settings));
        assertThat(exc.getMessage(), containsString("[세종] in user dictionary at line [4]"));
    }

    public void testCustomAnalyzerDuplicateUserDictRuleWithLegacyVersion() throws IOException {
        Settings settings = Settings.builder()
            .put("index.analysis.analyzer.my_analyzer.type", "custom_analyzer")
            .put(IndexMetadata.SETTING_VERSION_CREATED, IndexVersions.V_8_10_0)
            .putList("index.analysis.analyzer.my_analyzer.user_dictionary_rules", "c++", "C쁠쁠", "세종", "세종", "세종시 세종 시")
            .build();

        final TestAnalysis analysis = createTestAnalysis(settings);
        Analyzer analyzer = analysis.indexAnalyzers.get("my_analyzer");
        try (TokenStream stream = analyzer.tokenStream("", "세종")) {
            assertTokenStreamContents(stream, new String[] { "세종" });
        }
    }

    public void testCustomAnalyzerDuplicateUserDictRuleDeduplication() throws Exception {
        Settings settings = Settings.builder()
            .put("index.analysis.analyzer.my_analyzer.type", "custom_analyzer")
            .put("index.analysis.analyzer.my_analyzer.lenient", "true")
            .put(IndexMetadata.SETTING_VERSION_CREATED, IndexVersions.NORI_DUPLICATES)
            .putList("index.analysis.analyzer.my_analyzer.user_dictionary_rules", "c++", "C쁠쁠", "세종", "세종", "세종시 세종 시")
            .build();
        TestAnalysis analysis = createTestAnalysis(settings);
        Analyzer analyzer = analysis.indexAnalyzers.get("my_analyzer");
        try (TokenStream stream = analyzer.tokenStream("", "세종시")) {
            assertTokenStreamContents(stream, new String[] { "세종", "시" });
        }
    }

    public void testCustomTokenizer() throws Exception {
        Settings settings = Settings.builder()
            .put("index.analysis.tokenizer.my_tokenizer.type", "custom_tokenizer")
            .put("index.analysis.tokenizer.my_tokenizer.decompound_mode", "mixed")
            .build();
        TestAnalysis analysis = createTestAnalysis(settings);
        Tokenizer tokenizer = analysis.tokenizer.get("my_tokenizer").create();
        tokenizer.setReader(new StringReader("뿌리가 깊은 나무"));
        assertTokenStreamContents(tokenizer, new String[] { "뿌리", "가", "깊", "은", "나무" });
        tokenizer.setReader(new StringReader("가늠표"));
        assertTokenStreamContents(tokenizer, new String[] { "가늠표", "가늠", "표" });
        // discard_punctuation default(true)
        tokenizer.setReader(new StringReader("3.2개"));
        assertTokenStreamContents(tokenizer, new String[] { "3", "2", "개" });
    }

    public void testCustomTokenizerDiscardPunctuationOptionTrue() throws Exception {
        Settings settings = createDiscardPunctuationOption("true");
        TestAnalysis analysis = createTestAnalysis(settings);
        Tokenizer tokenizer = analysis.tokenizer.get("my_tokenizer").create();
        tokenizer.setReader(new StringReader("3.2개"));
        assertTokenStreamContents(tokenizer, new String[] { "3", "2", "개" });
    }

    public void testCustomTokenizerDiscardPunctuationOptionFalse() throws Exception {
        Settings settings = createDiscardPunctuationOption("false");
        TestAnalysis analysis = createTestAnalysis(settings);
        Tokenizer tokenizer = analysis.tokenizer.get("my_tokenizer").create();
        tokenizer.setReader(new StringReader("3.2개"));
        assertTokenStreamContents(tokenizer, new String[] { "3", ".", "2", "개" });
    }

    public void testCustomTokenizerInvalidDiscardPunctuationOption() {
        String wrongOption = "wrong";
        Settings settings = createDiscardPunctuationOption(wrongOption);
        IllegalArgumentException exc = expectThrows(IllegalArgumentException.class, () -> createTestAnalysis(settings));
        assertThat(exc.getMessage(), containsString("Failed to parse value [" + wrongOption + "] as only [true] or [false] are allowed."));
    }

    public void testCustomPartOfSpeech() throws IOException {
        Settings settings = Settings.builder()
            .put("index.analysis.filter.my_filter.type", "custom_part_of_speech")
            .put("index.analysis.filter.my_filter.stoptags", "NR, SP")
            .build();
        TestAnalysis analysis = createTestAnalysis(settings);
        TokenFilterFactory factory = analysis.tokenFilter.get("my_filter");
        Tokenizer tokenizer = new KoreanTokenizer();
        tokenizer.setReader(new StringReader("여섯 용이"));
        TokenStream stream = factory.create(tokenizer);
        assertTokenStreamContents(stream, new String[] { "용", "이" });
    }

    public void testCustomReadingForm() throws IOException {
        Settings settings = Settings.builder()
            .put(IndexMetadata.SETTING_VERSION_CREATED, IndexVersion.current())
            .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString())
            .put("index.analysis.filter.my_filter.type", "custom_readingform")
            .build();
        TestAnalysis analysis = AnalysisTestsHelper.createTestAnalysisFromSettings(settings, new AnalysisCustomPlugin());
        TokenFilterFactory factory = analysis.tokenFilter.get("my_filter");
        Tokenizer tokenizer = new KoreanTokenizer();
        tokenizer.setReader(new StringReader("鄕歌"));
        TokenStream stream = factory.create(tokenizer);
        assertTokenStreamContents(stream, new String[] { "향가" });
    }

    public void testCustomNumber() throws IOException {
        Settings settings = Settings.builder()
            .put(IndexMetadata.SETTING_VERSION_CREATED, IndexVersion.current())
            .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir().toString())
            .put("index.analysis.filter.my_filter.type", "custom_number")
            .build();
        TestAnalysis analysis = AnalysisTestsHelper.createTestAnalysisFromSettings(settings, new AnalysisCustomPlugin());
        TokenFilterFactory factory = analysis.tokenFilter.get("my_filter");
        Tokenizer tokenizer = new KoreanTokenizer();
        tokenizer.setReader(new StringReader("오늘 십만이천오백원짜리 와인 구입"));
        TokenStream stream = factory.create(tokenizer);
        assertTokenStreamContents(stream, new String[] { "오늘", "102500", "원", "짜리", "와인", "구입" });
    }

    private Settings createDiscardPunctuationOption(String option) {
        return Settings.builder()
            .put("index.analysis.tokenizer.my_tokenizer.type", "custom_tokenizer")
            .put("index.analysis.tokenizer.my_tokenizer.discard_punctuation", option)
            .build();
    }

    private TestAnalysis createTestAnalysis(Settings analysisSettings) throws IOException {
//        InputStream dict = MyCustomAnalysisTests.class.getResourceAsStream("user_dict.txt");
        Path home = createTempDir();
        Path config = home.resolve("config");
        Files.createDirectory(config);
//        Files.copy(dict, config.resolve("user_dict.txt"));
        Settings settings = Settings.builder()
            .put(IndexMetadata.SETTING_VERSION_CREATED, IndexVersion.current())
            .put(Environment.PATH_HOME_SETTING.getKey(), home)
            .put(analysisSettings)
            .build();
        TestAnalysis res = AnalysisTestsHelper.createTestAnalysisFromSettings(settings, new AnalysisCustomPlugin());
        return res;
    }
}
