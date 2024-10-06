/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.elasticsearch.plugin.analysis.custom;

import java.io.IOException;
import java.util.StringTokenizer;

import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.util.IgnoreRandomChains;

@IgnoreRandomChains(reason = "LUCENE-10359: fails with incorrect offsets")
public final class CustomTokenizer extends Tokenizer {
    private final CharTermAttribute charTermAttr = addAttribute(CharTermAttribute.class);
    private final OffsetAttribute offsetAttr = addAttribute(OffsetAttribute.class);
    private final PositionIncrementAttribute posIncrAttr = addAttribute(PositionIncrementAttribute.class);

    private final StringTokenizer tokenizer;  // 기본 Java의 StringTokenizer 사용
    private int tokenStart = 0;
    private int tokenEnd = 0;

    public CustomTokenizer() {
        // 입력 스트림을 읽어들이고 이를 StringTokenizer에 전달
        StringBuilder inputText = new StringBuilder();
        try {
            char[] buffer = new char[256];
            int length;
            while ((length = input.read(buffer)) != -1) {
                inputText.append(buffer, 0, length);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // 공백과 '|'를 기준으로 토큰화
        tokenizer = new StringTokenizer(inputText.toString(), " |");
    }

    @Override
    public boolean incrementToken() {
        clearAttributes();  // 이전 토큰 정보 초기화

        if (tokenizer.hasMoreTokens() == false) {
            return false;  // 토큰이 더 이상 없으면 종료
        }

        String token = tokenizer.nextToken();  // 다음 토큰 가져오기

        // 현재 토큰의 위치 및 길이를 설정
        tokenStart = tokenEnd;
        tokenEnd = tokenStart + token.length();

        // 토큰의 속성 설정
        charTermAttr.append(token);  // 실제 토큰 문자열 설정
        charTermAttr.setLength(token.length());
        offsetAttr.setOffset(tokenStart, tokenEnd);  // 토큰의 오프셋 설정
        posIncrAttr.setPositionIncrement(1);  // 기본적으로 position 증가값 1로 설정

        return true;  // 토큰이 있으면 true 반환
    }

    @Override
    public void reset() throws IOException {
        super.reset();
        tokenStart = tokenEnd = 0;  // 토큰 시작과 끝 초기화
    }

    @Override
    public void end() throws IOException {
        super.end();
        offsetAttr.setOffset(tokenEnd, tokenEnd);  // 마지막 오프셋 설정
    }
}
