package com.example.streaming.parser;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Jackson streaming parser for memory-efficient JSON parsing.
 *
 * <p>This parser uses Jackson's low-level streaming API to parse JSON
 * without building a full object tree in memory. It streams individual
 * items from a JSON array one at a time.
 *
 * <p>Use this for scenarios where individual pages contain a large number
 * of items and you want to minimize memory usage even within a single page.
 *
 * <p>Example JSON structure:
 * <pre>{@code
 * {
 *   "data": [
 *     {"id": "1", "amount": 100.0, "status": "completed"},
 *     {"id": "2", "amount": 200.0, "status": "pending"}
 *   ],
 *   "nextCursor": "abc123",
 *   "hasMore": true
 * }
 * }</pre>
 *
 * @param <T> the type of items to parse
 */
public class StreamingJsonParser<T> {

    private final ObjectMapper objectMapper;
    private final JavaType itemType;
    private final String arrayFieldName;

    /**
     * Creates a parser for items in the "data" field.
     */
    public StreamingJsonParser(Class<T> itemClass) {
        this(itemClass, "data");
    }

    /**
     * Creates a parser for items in a custom field.
     *
     * @param itemClass the class of items to parse
     * @param arrayFieldName the name of the JSON field containing the array
     */
    public StreamingJsonParser(Class<T> itemClass, String arrayFieldName) {
        this.objectMapper = new ObjectMapper();
        this.itemType = objectMapper.getTypeFactory().constructType(itemClass);
        this.arrayFieldName = arrayFieldName;
    }

    /**
     * Creates a parser with a custom ObjectMapper.
     */
    public StreamingJsonParser(ObjectMapper objectMapper, Class<T> itemClass, String arrayFieldName) {
        this.objectMapper = objectMapper;
        this.itemType = objectMapper.getTypeFactory().constructType(itemClass);
        this.arrayFieldName = arrayFieldName;
    }

    /**
     * Parses items from an input stream, returning a lazy stream.
     *
     * <p>Items are parsed one at a time as the stream is consumed.
     * The input stream should be closed by the caller after the stream
     * is fully consumed.
     *
     * @param inputStream the JSON input stream
     * @return a lazy stream of parsed items
     * @throws IOException if JSON parsing fails
     */
    public Stream<T> parseItems(InputStream inputStream) throws IOException {
        JsonParser parser = objectMapper.getFactory().createParser(inputStream);

        // Navigate to the array field
        if (!navigateToArray(parser)) {
            parser.close();
            return Stream.empty();
        }

        // Create a lazy stream from the array elements
        Spliterator<T> spliterator = new JsonArraySpliterator<>(parser, objectMapper, itemType);
        return StreamSupport.stream(spliterator, false)
                .onClose(() -> closeQuietly(parser));
    }

    /**
     * Navigates the parser to the start of the array field.
     *
     * @return true if the array was found
     */
    private boolean navigateToArray(JsonParser parser) throws IOException {
        while (parser.nextToken() != null) {
            if (parser.currentToken() == JsonToken.FIELD_NAME
                    && arrayFieldName.equals(parser.currentName())) {
                JsonToken next = parser.nextToken();
                if (next == JsonToken.START_ARRAY) {
                    return true;
                }
            }
        }
        return false;
    }

    private void closeQuietly(JsonParser parser) {
        try {
            parser.close();
        } catch (IOException e) {
            // Ignore close errors
        }
    }

    /**
     * Spliterator that reads items from a JSON array one at a time.
     */
    private static class JsonArraySpliterator<E> extends Spliterators.AbstractSpliterator<E> {

        private final JsonParser parser;
        private final ObjectMapper objectMapper;
        private final JavaType itemType;
        private boolean finished = false;

        protected JsonArraySpliterator(JsonParser parser, ObjectMapper objectMapper, JavaType itemType) {
            super(Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL);
            this.parser = parser;
            this.objectMapper = objectMapper;
            this.itemType = itemType;
        }

        @Override
        public boolean tryAdvance(Consumer<? super E> action) {
            if (finished) {
                return false;
            }

            try {
                JsonToken token = parser.nextToken();

                if (token == null || token == JsonToken.END_ARRAY) {
                    finished = true;
                    return false;
                }

                // Parse the current object
                E item = objectMapper.readValue(parser, itemType);
                action.accept(item);
                return true;
            } catch (IOException e) {
                finished = true;
                throw new UncheckedIOException("Failed to parse JSON item", e);
            }
        }
    }

    /**
     * Result holder for parsing that includes both items stream and metadata.
     */
    public record ParseResult<T>(
            Stream<T> items,
            String nextCursor,
            boolean hasMore
    ) {
    }

    /**
     * Parses both items and pagination metadata from the response.
     *
     * <p>Note: This method reads metadata fields after the array,
     * assuming the JSON structure has metadata after the data array.
     * For better compatibility, consider using a two-pass approach
     * or restructuring the JSON to have metadata first.
     */
    public ParseResult<T> parseWithMetadata(InputStream inputStream) throws IOException {
        JsonParser parser = objectMapper.getFactory().createParser(inputStream);

        String nextCursor = null;
        boolean hasMore = false;

        // First pass: find metadata fields that appear before the array
        while (parser.nextToken() != null) {
            if (parser.currentToken() == JsonToken.FIELD_NAME) {
                String fieldName = parser.currentName();

                if ("nextCursor".equals(fieldName)) {
                    parser.nextToken();
                    nextCursor = parser.getValueAsString();
                } else if ("hasMore".equals(fieldName)) {
                    parser.nextToken();
                    hasMore = parser.getValueAsBoolean();
                } else if (arrayFieldName.equals(fieldName)) {
                    parser.nextToken(); // Move to START_ARRAY
                    break;
                }
            }
        }

        // Create stream from the array
        Spliterator<T> spliterator = new JsonArraySpliterator<>(parser, objectMapper, itemType);
        Stream<T> itemStream = StreamSupport.stream(spliterator, false)
                .onClose(() -> closeQuietly(parser));

        return new ParseResult<>(itemStream, nextCursor, hasMore);
    }
}
