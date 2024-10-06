/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.plugin.analysis.custom;

import org.apache.lucene.analysis.ko.KoreanAnalyzer;
import org.apache.lucene.analysis.ko.KoreanPartOfSpeechStopFilter;
import org.apache.lucene.analysis.ko.KoreanTokenizer;
import org.apache.lucene.analysis.ko.POS;
import org.apache.lucene.analysis.ko.dict.UserDictionary;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.AbstractIndexAnalyzerProvider;
import org.elasticsearch.index.analysis.Analysis;
import org.elasticsearch.index.analysis.CharFilterFactory;
import org.elasticsearch.index.analysis.TokenFilterFactory;
import org.elasticsearch.index.analysis.CustomAnalyzer;

import java.util.List;
import java.util.Set;

import static org.elasticsearch.plugin.analysis.custom.CustomPartOfSpeechStopFilterFactory.resolvePOSList;

public class CustomAnalyzerProvider extends AbstractIndexAnalyzerProvider<CustomAnalyzer> {
//    private final KoreanAnalyzer analyzer;

    private final CustomAnalyzer analyzer2;

    public CustomAnalyzerProvider(IndexSettings indexSettings, Environment env, String name, Settings settings) {
        super(name, settings);
//        final KoreanTokenizer.DecompoundMode mode = CustomTokenizerFactory.getMode(settings);
//        final UserDictionary userDictionary = CustomTokenizerFactory.getUserDictionary(env, settings, indexSettings);
//        final List<String> tagList = Analysis.getWordList(env, settings, "stoptags");
//        final Set<POS.Tag> stopTags = tagList != null ? resolvePOSList(tagList) : KoreanPartOfSpeechStopFilter.DEFAULT_STOP_TAGS;
//        analyzer = new KoreanAnalyzer(userDictionary, mode, stopTags, false);

        CharFilterFactory[] charFilters = new CharFilterFactory[] {};
        TokenFilterFactory[] tokenFilters = new TokenFilterFactory[] {};

        analyzer2 = new CustomAnalyzer(new CustomTokenizerFactory(indexSettings, settings, name), charFilters, tokenFilters);
    }

    @Override
    public CustomAnalyzer get() {
        return analyzer2;
    }

}
