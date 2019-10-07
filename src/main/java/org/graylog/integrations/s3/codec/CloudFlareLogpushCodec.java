package org.graylog.integrations.s3.codec;

import com.amazonaws.util.StringUtils;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.graylog.integrations.s3.config.Configuration;
import org.graylog2.gelfclient.GelfMessage;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CloudFlareLogpushCodec extends AbstractS3Codec implements S3Codec {

    static final Logger LOG = LogManager.getLogger(CloudFlareLogpushCodec.class);
    private static final List<String> TIMESTAMP_FIELDS = Arrays.asList("EdgeEndTimestamp", "EdgeStartTimestamp");
    private static final List<String> HTTP_CODE_FIELDS = Arrays.asList("CacheResponseStatus", "EdgeResponseStatus", "OriginResponseStatus");

    CloudFlareLogpushCodec(String stringMessage, Configuration config) {
        super(stringMessage, config);
    }

    public GelfMessage decode() throws IOException {

        final ObjectMapper mapper = new ObjectMapper();
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
              .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);

        // The valueMap makes it easier to get access to each field.
        HashMap<String, Object> valueMap = mapper.readValue(stringMessage, HashMap.class);
        final JsonNode entireJsonNode = mapper.readTree(stringMessage);

        final Map<String, Object> messageMap = new LinkedHashMap<>();

        // Prepare message summary. Use fields indicated in the configuration.
        Arrays.stream(config.getLogpushConfiguration().getMessageSummaryFields().split(","))
              .map(String::trim)
              .filter(s -> !s.isEmpty())
              .filter(valueMap::containsKey)
              .forEach(s -> messageMap.put(s, valueMap.get(s)));
        // The resulting message looks like:
        // ClientRequestHost: domain.com:8080 | ClientRequestPath: /api/cluster/metrics/multiple | OriginIP: 127.0.68.0 | ClientSrcPort: 54728 | EdgeServerIP: 127.0.68.0 | EdgeResponseBytes: 911
        final String messageSummary = messageMap.keySet().stream().map(key -> key + ": " + valueMap.get(key)).collect(Collectors.joining(" | "));

        final GelfMessage message = new GelfMessage(messageSummary, config.getGraylogHost());

        // Set message timestamp. Timestamp defaults to now, so no need to set when the useNowTimestamp = false.
        if (!config.getLogpushConfiguration().getUseNowTimestamp()) {
            final JsonNode edgeStartTimestamp = entireJsonNode.findValue("EdgeStartTimestamp");
            if (edgeStartTimestamp != null) {
                final double timestamp = parseTimestamp(edgeStartTimestamp);
                message.setTimestamp(timestamp);
            } else {
                // Default to now.
                message.setTimestamp(Instant.now().getEpochSecond());
            }
        }

        // Get a list of parsed fields to include in the message.
        List<String> fieldNamesToInclude = getFieldsToInclude(valueMap);

        Iterator<Map.Entry<String, JsonNode>> fieldIterator = entireJsonNode.fields();
        while (fieldIterator.hasNext()) {
            Map.Entry<String, JsonNode> fieldPair = fieldIterator.next();
            final String key = fieldPair.getKey();
            final JsonNode valueNode = fieldPair.getValue();

            // Skip fields not indicated to be included in the Config.messageFields field.
            // An empty value means all fields should be included.
            if (fieldNamesToInclude != null && !fieldNamesToInclude.contains(key)) {
                continue;
            }

            if (!valueNode.isArray()) {

                // Pick off and parse timestamp fields first.
                if (TIMESTAMP_FIELDS.contains(key)) {

                    // If textual, then Timestamp is in RFC 3339 format: 2019-09-13T20:23:09Z
                    if (valueNode.isTextual()) {
                        final long timestamp = Instant.parse(valueNode.textValue()).getEpochSecond();
                        message.addAdditionalField(key, timestamp);
                    } else if (valueNode.isInt()) {
                        // Unix timestamp in seconds.
                        // Int automatically casts to a double.
                        message.addAdditionalField(key, valueNode.intValue());
                    } else if (valueNode.isLong()) {
                        // Unix nanos
                        // Move the decimal place 9 places and cast to a double.
                        final double timestampFractionalSeconds = (double) valueNode.longValue() / 1_000_000_000;
                        message.addAdditionalField(key, timestampFractionalSeconds);
                    }
                    continue;
                }

                // Set status class for all status fields (eg. "4xx" for 400 and 412)
                if (HTTP_CODE_FIELDS.contains(key)) {
                    final Object nodeValue = getNodeValue(valueNode);
                    if (nodeValue != null) {
                        final int statusValue = (int) nodeValue;
                        if (statusValue >= 100 && statusValue < 200) {
                            message.addAdditionalField(key + "Class", "1xx");
                        }
                        else if (statusValue >= 200 && statusValue < 300) {
                            message.addAdditionalField(key + "Class", "2xx");
                        }
                        else if (statusValue >= 300 && statusValue < 400) {
                            message.addAdditionalField(key + "Class", "3xx");
                        }
                        else if (statusValue >= 400 && statusValue < 500) {
                            message.addAdditionalField(key + "Class", "4xx");
                        }
                        else if (statusValue >= 500 && statusValue < 600) {
                            message.addAdditionalField(key + "Class", "5xx");
                        }
                    }
                }

                // Set response time millis.
                if ("OriginResponseTime".equals(key)) {
                    final Object nodeValue = getNodeValue(valueNode);
                    if (nodeValue != null) {
                        message.addAdditionalField("OriginResponseTimeMillis", Double.valueOf((Integer) nodeValue) / 1_000_000);
                    }
                }

                // Scalar values can be written directly to the GelfMessage.
                message.addAdditionalField(key, getNodeValue(valueNode));
            } else {
                // TODO: The Initial version can be shipped without support for lists.
                //  Arrays can be comma-separated. See these examples:
                // "FirewallMatchesActions": [
                //    "allow"
                // ],
                //"FirewallMatchesSources": [
                //    "firewallRules"
                // ],
                //"FirewallMatchesRuleIDs": [
                //    "test"
                // ],
                // message.addAdditionalField(key, valueNode.toString());
            }
        }

        return message;
    }

    private List<String> getFieldsToInclude(HashMap<String, Object> valueMap) {
        List<String> fieldNamesToInclude = null;
        if (!StringUtils.isNullOrEmpty(config.getLogpushConfiguration().getMessageFields())) {
            fieldNamesToInclude = Arrays.stream(config.getLogpushConfiguration().getMessageFields().split(","))
                                        .map(String::trim)
                                        .filter(s -> !s.isEmpty())
                                        .filter(valueMap::containsKey)
                                        .collect(Collectors.toList());
        }
        return fieldNamesToInclude;
    }

    /**
     * Selects the node value based on its datatype.
     */
    private static Object getNodeValue(JsonNode node) {
        if (node.isNumber()) {
            return node.numberValue();
        } else if (node.isBoolean()) {
            return node.booleanValue();
        } else if (node.isTextual()) {
            return node.textValue();
        }

        throw new IllegalArgumentException("Invalid node type [" + node.getNodeType() + "].");
    }

    /**
     * The Cloudflare timestamp may arrive in one of the following formats based on the settings in Cloudflare.
     * We support them all.
     *
     * - RFC3339 (2019-10-07T16:00:00Z)
     * - Unix (1570464000)
     * - UnixNano (1570465372184306580) Note that only millisecond precision will be stored in graylog. See http://graylog2.org/gelf#specs.
     *
     * @param node The JSONNode containing the timestamp.
     * @return a
     */
    private static double parseTimestamp(JsonNode node) {
        if (node.isTextual()) {
            // RFC3339 format
            return Instant.parse(node.textValue()).getEpochSecond();
        } else if (node.isInt()) {
            // Unix timestamp
            return node.intValue();
        } else if (node.isLong()) {
            // Unix nano timestamp
            return (double) node.longValue() / 1_000_000_000;
        }

        throw new IllegalArgumentException("Invalid Timestamp type [" + node.getNodeType() + "]. " +
                                           "Expected a string, an integer, or a long value.");
    }
}
