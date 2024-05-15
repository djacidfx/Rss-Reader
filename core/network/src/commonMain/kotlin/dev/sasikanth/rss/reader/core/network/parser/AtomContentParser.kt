/*
 * Copyright 2023 Sasikanth Miriyampalli
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.sasikanth.rss.reader.core.network.parser

import dev.sasikanth.rss.reader.core.model.remote.FeedPayload
import dev.sasikanth.rss.reader.core.model.remote.PostPayload
import dev.sasikanth.rss.reader.core.network.parser.FeedParser.Companion.ATTR_HREF
import dev.sasikanth.rss.reader.core.network.parser.FeedParser.Companion.ATTR_REL
import dev.sasikanth.rss.reader.core.network.parser.FeedParser.Companion.ATTR_VALUE_ALTERNATE
import dev.sasikanth.rss.reader.core.network.parser.FeedParser.Companion.TAG_ATOM_ENTRY
import dev.sasikanth.rss.reader.core.network.parser.FeedParser.Companion.TAG_ATOM_FEED
import dev.sasikanth.rss.reader.core.network.parser.FeedParser.Companion.TAG_CONTENT
import dev.sasikanth.rss.reader.core.network.parser.FeedParser.Companion.TAG_LINK
import dev.sasikanth.rss.reader.core.network.parser.FeedParser.Companion.TAG_PUBLISHED
import dev.sasikanth.rss.reader.core.network.parser.FeedParser.Companion.TAG_SUBTITLE
import dev.sasikanth.rss.reader.core.network.parser.FeedParser.Companion.TAG_SUMMARY
import dev.sasikanth.rss.reader.core.network.parser.FeedParser.Companion.TAG_TITLE
import dev.sasikanth.rss.reader.core.network.parser.FeedParser.Companion.TAG_UPDATED
import dev.sasikanth.rss.reader.util.dateStringToEpochMillis
import dev.sasikanth.rss.reader.util.decodeUrlEncodedString
import io.ktor.http.Url
import kotlinx.datetime.Clock
import org.kobjects.ktxml.api.EventType
import org.kobjects.ktxml.api.XmlPullParser

internal object AtomContentParser : ContentParser() {

  override fun parse(feedUrl: String, parser: XmlPullParser): FeedPayload {
    parser.require(EventType.START_TAG, parser.namespace, TAG_ATOM_FEED)

    val posts = mutableListOf<PostPayload?>()

    var title: String? = null
    var description: String? = null
    var link: String? = null

    while (parser.next() != EventType.END_TAG) {
      if (parser.eventType != EventType.START_TAG) continue
      when (val name = parser.name) {
        TAG_TITLE -> {
          title = parser.nextText()
        }
        TAG_LINK -> {
          if (link.isNullOrBlank()) {
            link = readAtomLink(name, parser)
          } else {
            parser.skip()
          }
        }
        TAG_SUBTITLE -> {
          description = parser.nextText()
        }
        TAG_ATOM_ENTRY -> {
          posts.add(readAtomEntry(parser, link))
        }
        else -> parser.skip()
      }
    }

    if (link.isNullOrBlank()) {
      link = feedUrl
    }

    val domain = Url(link)
    val host =
      if (domain.host != "localhost") {
        domain.host
      } else {
        throw NullPointerException("Unable to get host domain")
      }
    val iconUrl = FeedParser.feedIcon(host)

    return FeedPayload(
      name = FeedParser.cleanText(title ?: link)!!.decodeUrlEncodedString(),
      description = FeedParser.cleanText(description).orEmpty().decodeUrlEncodedString(),
      icon = iconUrl,
      homepageLink = link,
      link = feedUrl,
      posts = posts.filterNotNull()
    )
  }

  private fun readAtomEntry(parser: XmlPullParser, hostLink: String?): PostPayload? {
    parser.require(EventType.START_TAG, null, "entry")

    var title: String? = null
    var link: String? = null
    var content: String? = null
    var rawContent: String? = null
    var date: String? = null
    var image: String? = null

    while (parser.next() != EventType.END_TAG) {
      if (parser.eventType != EventType.START_TAG) continue

      when (val tagName = parser.name) {
        TAG_TITLE -> {
          title = parser.nextText()
        }
        TAG_LINK -> {
          if (link.isNullOrBlank()) {
            link = readAtomLink(tagName, parser)
          } else {
            parser.skip()
          }
        }
        TAG_CONTENT,
        TAG_SUMMARY -> {
          rawContent = parser.nextText().trimIndent()

          val htmlContent = HtmlContentParser.parse(htmlContent = rawContent)
          if (image.isNullOrBlank() && htmlContent != null) {
            image = htmlContent.imageUrl
          }

          content = htmlContent?.content?.ifBlank { rawContent.trim() } ?: rawContent.trim()
        }
        TAG_PUBLISHED,
        TAG_UPDATED -> {
          if (date.isNullOrBlank()) {
            date = parser.nextText()
          } else {
            parser.skip()
          }
        }
        else -> parser.skip()
      }
    }

    val postPubDateInMillis = date?.let { dateString -> dateString.dateStringToEpochMillis() }

    if (title.isNullOrBlank() && content.isNullOrBlank()) {
      return null
    }

    return PostPayload(
      link = FeedParser.cleanText(link)!!,
      title = FeedParser.cleanText(title).orEmpty().decodeUrlEncodedString(),
      description = FeedParser.cleanTextCompact(content).orEmpty().decodeUrlEncodedString(),
      rawContent = rawContent,
      imageUrl = FeedParser.safeUrl(hostLink, image),
      date = postPubDateInMillis ?: Clock.System.now().toEpochMilliseconds(),
      commentsLink = null
    )
  }

  private fun readAtomLink(tagName: String, parser: XmlPullParser): String? {
    var link: String? = null
    parser.require(EventType.START_TAG, parser.namespace, tagName)
    val relType = parser.getAttributeValue(parser.namespace, ATTR_REL)
    if (relType == ATTR_VALUE_ALTERNATE || relType.isNullOrBlank()) {
      link = parser.getAttributeValue(parser.namespace, ATTR_HREF)
    }
    parser.nextTag()
    parser.require(EventType.END_TAG, parser.namespace, tagName)
    return link
  }
}
