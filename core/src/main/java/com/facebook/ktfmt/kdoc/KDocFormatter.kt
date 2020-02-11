/*
 * Copyright 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

/*
 * This was copied from https://github.com/google/google-java-format and modified extensively to
 * work for Kotlin formatting
 */

package com.facebook.ktfmt.kdoc

import com.facebook.ktfmt.kdoc.Token.Type.BEGIN_KDOC
import com.facebook.ktfmt.kdoc.Token.Type.BLANK_LINE
import com.facebook.ktfmt.kdoc.Token.Type.END_KDOC
import com.facebook.ktfmt.kdoc.Token.Type.LIST_ITEM_OPEN_TAG
import com.facebook.ktfmt.kdoc.Token.Type.LITERAL
import com.facebook.ktfmt.kdoc.Token.Type.WHITESPACE
import java.util.regex.Pattern.compile
import org.jetbrains.kotlin.lexer.KtTokens.WHITE_SPACE

import com.google.common.collect.ImmutableList
import com.intellij.psi.tree.IElementType
import org.jetbrains.kotlin.kdoc.lexer.KDocLexer
import org.jetbrains.kotlin.kdoc.lexer.KDocTokens

/**
 * Entry point for formatting KDoc.
 *
 * This stateless class reads tokens from the stateful lexer and translates them to "requests"
 * and "writes" to the stateful writer. It also munges tokens into "standardized" forms. Finally, it
 * performs postprocessing to convert the written KDoc to a one-liner if possible or to leave a
 * single blank line if it's empty.
 */
object KDocFormatter {

  internal val MAX_LINE_LENGTH = 100

  private val ONE_CONTENT_LINE_PATTERN = compile(" */[*][*]\n *[*] (.*)\n *[*]/")

  /**
   * Formats the given Javadoc comment, which must start with ∕✱✱ and end with ✱∕. The output will
   * start and end with the same characters.
   */
  fun formatKDoc(input: String, blockIndent: Int): String {
    val kDocLexer = KDocLexer()
    kDocLexer.start(input)
    val newTokensBuilder = ImmutableList.Builder<Token>()
    var previousType: IElementType? = null
    while (kDocLexer.tokenType != null) {
      val tokenType = kDocLexer.tokenType
      val tokenText = kDocLexer.tokenText
      if (tokenType === KDocTokens.START) {
        newTokensBuilder.add(Token(BEGIN_KDOC, tokenText))
      } else if (tokenType === KDocTokens.LEADING_ASTERISK) {
        // ignore
      } else if (tokenType === KDocTokens.END) {
        newTokensBuilder.add(Token(END_KDOC, tokenText))
      } else if (tokenType === KDocTokens.TEXT) {
        val trimmedText = tokenText.trim { it <= ' ' }
        if (!trimmedText.isEmpty()) {
          val words = trimmedText.split(" +".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
          var first = true
          for (word in words) {
            if (first) {
              if (word == "-") {
                newTokensBuilder.add(Token(LIST_ITEM_OPEN_TAG, ""))
              }
              first = false
            }
            newTokensBuilder.add(Token(LITERAL, word))
            newTokensBuilder.add(Token(WHITESPACE, " "))
          }
        }
      } else if (tokenType === KDocTokens.TAG_NAME) {
        newTokensBuilder.add(Token(LITERAL, tokenText.trim { it <= ' ' }))
      } else if (tokenType === KDocTokens.CODE_BLOCK_TEXT) {
        val trimmedFirstSpaceText = if (tokenText.first() == ' ') tokenText.substring(1) else tokenText
        newTokensBuilder.add(Token(LITERAL, trimmedFirstSpaceText))
      } else if (tokenType === KDocTokens.MARKDOWN_INLINE_LINK) {
        newTokensBuilder.add(Token(LITERAL, tokenText))
      } else if (tokenType === KDocTokens.MARKDOWN_LINK) {
        newTokensBuilder.add(Token(LITERAL, tokenText))
      } else if (tokenType === WHITE_SPACE) {
        if (previousType === KDocTokens.TAG_NAME || previousType === KDocTokens.MARKDOWN_LINK) {
          newTokensBuilder.add(Token(WHITESPACE, " "))
        } else {
          newTokensBuilder.add(Token(BLANK_LINE, ""))
        }
      } else {
        throw RuntimeException("Unexpected: " + tokenType!!)
      }

      previousType = tokenType
      kDocLexer.advance()
    }
    val result = render(newTokensBuilder.build(), blockIndent)
    return makeSingleLineIfPossible(blockIndent, result)
  }

  private fun render(input: List<Token>, blockIndent: Int): String {
    val output = KDocWriter(blockIndent)
    for (token in input) {
      when (token.type) {
        BEGIN_KDOC -> output.writeBeginJavadoc()
        END_KDOC -> {
          output.writeEndJavadoc()
          return output.toString()
        }
        LIST_ITEM_OPEN_TAG -> output.writeListItemOpen(token)
        Token.Type.PRE_OPEN_TAG -> output.writePreOpen(token)
        Token.Type.PRE_CLOSE_TAG -> output.writePreClose(token)
        Token.Type.CODE_OPEN_TAG -> output.writeCodeOpen(token)
        Token.Type.CODE_CLOSE_TAG -> output.writeCodeClose(token)
        Token.Type.TABLE_OPEN_TAG -> output.writeTableOpen(token)
        Token.Type.TABLE_CLOSE_TAG -> output.writeTableClose(token)
        BLANK_LINE -> output.writeKDocWhitespace()
        WHITESPACE -> output.requestWhitespace()
        LITERAL -> output.writeLiteral(token)
        else -> throw AssertionError(token.type)
      }
    }
    throw AssertionError()
  }

  /**
   * Returns the given string or a one-line version of it (e.g., "∕✱✱ Tests for foos. ✱∕") if it
   * fits on one line.
   */
  private fun makeSingleLineIfPossible(blockIndent: Int, input: String): String {
    val oneLinerContentLength = MAX_LINE_LENGTH - "/**  */".length - blockIndent
    val matcher = ONE_CONTENT_LINE_PATTERN.matcher(input)
    if (matcher.matches() && matcher.group(1).isEmpty()) {
      return "/** */"
    } else if (matcher.matches() && matcher.group(1).length <= oneLinerContentLength) {
      return "/** " + matcher.group(1) + " */"
    }
    return input
  }
}