/*
 * Copyright (c) Mirth Corporation. All rights reserved.
 * 
 * http://www.mirthcorp.com
 * 
 * The software in this package is published under the terms of the MPL license a copy of which has
 * been included with this distribution in the LICENSE.txt file.
 */

package com.mirth.connect.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import javax.xml.XMLConstants;
import javax.xml.stream.FactoryConfigurationError;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.stax.StAXResult;
import javax.xml.transform.stax.StAXSource;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.Pair;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import de.odysseus.staxon.json.JsonXMLConfig;
import de.odysseus.staxon.json.JsonXMLConfigBuilder;
import de.odysseus.staxon.json.JsonXMLInputFactory;
import de.odysseus.staxon.json.JsonXMLOutputFactory;
import de.odysseus.staxon.json.stream.JsonStreamFactory;
import de.odysseus.staxon.json.stream.JsonStreamSource;
import de.odysseus.staxon.json.stream.JsonStreamTarget;
import de.odysseus.staxon.xml.util.PrettyXMLStreamWriter;

public class JsonXmlUtil {
	
	private static final String SEPARATOR = ":";

	public static String xmlToJson(String xmlStr) throws IOException, XMLStreamException, FactoryConfigurationError,
			TransformerConfigurationException, TransformerException, TransformerFactoryConfigurationError {
		return xmlToJson(xmlStr, true);
	}

    public static String xmlToJson(String xmlStr, boolean normalizeNamespaces) throws IOException, XMLStreamException, FactoryConfigurationError, TransformerConfigurationException, TransformerException, TransformerFactoryConfigurationError {
        //convert xml to json
        JsonXMLConfig config = new JsonXMLConfigBuilder().autoArray(true).autoPrimitive(true).prettyPrint(false).build();
        return xmlToJson(config, xmlStr, normalizeNamespaces);
    }

    public static String xmlToJson(JsonXMLConfig config, String xmlStr, boolean normalizeNamespaces) throws IOException, XMLStreamException, FactoryConfigurationError, TransformerConfigurationException, TransformerException, TransformerFactoryConfigurationError {
        try (InputStream inputStream = IOUtils.toInputStream(xmlStr);
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            // create source (XML)
            XMLStreamReader reader = XMLInputFactory.newInstance().createXMLStreamReader(inputStream);
            Source source = new StAXSource(reader);

            // create result (JSON)
            JsonStreamFactory streamFactory = new CorrectedJsonStreamFactory();
            XMLStreamWriter writer = new JsonXMLOutputFactory(config, streamFactory).createXMLStreamWriter(outputStream);
            Result result = new StAXResult(writer);

            // copy source to result via "identity transform"
            TransformerFactory.newInstance().newTransformer().transform(source, result);
            String jsonString = outputStream.toString();
            
            if (normalizeNamespaces) {
            	jsonString = normalizeNamespaces(jsonString, config.isPrettyPrint());
            }
            
            return jsonString;
        }
    }

    public static String jsonToXml(String jsonStr) throws IOException, XMLStreamException, FactoryConfigurationError, TransformerConfigurationException, TransformerException, TransformerFactoryConfigurationError {
        JsonXMLConfig config = new JsonXMLConfigBuilder().multiplePI(false).build();
        return jsonToXml(config, jsonStr);
    }

    public static String jsonToXml(JsonXMLConfig config, String jsonStr) throws IOException, XMLStreamException, FactoryConfigurationError, TransformerConfigurationException, TransformerException, TransformerFactoryConfigurationError {
        try (InputStream inputStream = IOUtils.toInputStream(jsonStr);
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            XMLStreamReader reader = new JsonXMLInputFactory(config).createXMLStreamReader(inputStream);
            Source source = new StAXSource(reader);

            XMLStreamWriter writer = XMLOutputFactory.newInstance().createXMLStreamWriter(outputStream);
            if (config.isPrettyPrint()) {
                writer = new PrettyXMLStreamWriter(writer);
            }
            Result result = new StAXResult(writer);

            TransformerFactory.newInstance().newTransformer().transform(source, result);
            return outputStream.toString();
        }
    }

	private static String normalizeNamespaces(String jsonString, boolean prettyPrint) {
		try {
			ObjectMapper mapper = new ObjectMapper();
			if (prettyPrint) {
				mapper.enable(SerializationFeature.INDENT_OUTPUT);
			}
			JsonNode jsonObject = mapper.readValue(jsonString, JsonNode.class);
			return mapper.writeValueAsString(normalizeJsonObject(jsonObject));
		} catch (Exception e) {
			return jsonString;
		}
	}

	private static LinkedHashMap<String, Object> normalizeJsonObject(JsonNode jsonObject) {
		// Using LinkedHashMaps to preserve order of fields
		LinkedHashMap<String, Object> normalizedJsonObject = new LinkedHashMap<>();
		normalizeJsonObject(jsonObject, null, null, new HashMap<>(), normalizedJsonObject);
		return normalizedJsonObject;
	}

	private static void normalizeJsonObject(JsonNode jsonObject, String jsonObjectKey, String currentNamespace,
			Map<String, String> namespacesByPrefix, Map<String, Object> normalizedJsonObject) {

		Iterator<Map.Entry<String, JsonNode>> it = jsonObject.fields();
		while (it.hasNext()) {
			Entry<String, JsonNode> field = it.next();
			String key = field.getKey();

			Pair<String, String> splitName = splitPrefixAndLocalName(key);
			String prefix = splitName.getLeft();
			String localName = splitName.getRight();

			// "@xmlns" can be either the prefix or localName, depending on if
			// there is a bound prefix
			if (prefix.equals("@xmlns") || localName.equals("@xmlns")) {
				String nsPrefix = prefix.equals("@xmlns") ? localName : XMLConstants.DEFAULT_NS_PREFIX;
				String namespace = field.getValue().asText();
				namespacesByPrefix.put(nsPrefix, namespace);

				// Only add the @xmlns attribute if the current namespace is
				// changing
				if (splitPrefixAndLocalName(jsonObjectKey).getLeft().equals(nsPrefix)
						&& (currentNamespace == null || !namespace.equals(currentNamespace))) {
					normalizedJsonObject.put("@xmlns", namespace);
					currentNamespace = namespace;
				}
			} else if (field.getValue().isObject()) {
				JsonNode innerJsonObject = field.getValue();
				LinkedHashMap<String, Object> innerNormalizedObject = new LinkedHashMap<>();

				// If the inner JSON object does not contain an @xmlns
				// attribute, we need to add one to the normalized object if the
				// inner object's namespace isn't the current namespace
				String namespaceTag = "@xmlns" + (XMLConstants.DEFAULT_NS_PREFIX.equals(prefix) ? "" : (":" + prefix));
				Map<String, String> innerNamespacesByPrefix = new HashMap<>(namespacesByPrefix);
				String innerCurrentNamespace = currentNamespace;

				if (!innerJsonObject.has(namespaceTag)) {
					if (namespacesByPrefix.containsKey(prefix)
							&& !namespacesByPrefix.get(prefix).equals(currentNamespace)) {
						String namespace = namespacesByPrefix.get(prefix);
						innerNormalizedObject.put("@xmlns", namespace);
						innerCurrentNamespace = namespace;
					} else if (!namespacesByPrefix.containsKey(prefix)
							&& !XMLConstants.NULL_NS_URI.equals(currentNamespace)) {
						innerNormalizedObject.put("@xmlns", XMLConstants.NULL_NS_URI);
						innerCurrentNamespace = XMLConstants.NULL_NS_URI;
					}
				}

				normalizedJsonObject.put(localName, innerNormalizedObject);
				normalizeJsonObject(innerJsonObject, key, innerCurrentNamespace, innerNamespacesByPrefix,
						innerNormalizedObject);
			} else {
				if (localName.startsWith("$") || localName.startsWith("@")) {
					normalizedJsonObject.put(localName, field.getValue());
				} else if (namespacesByPrefix.containsKey(prefix)
						&& !namespacesByPrefix.get(prefix).equals(currentNamespace)) {
					LinkedHashMap<String, Object> innerNormalizedObject = new LinkedHashMap<>();
					String namespace = namespacesByPrefix.get(prefix);
					innerNormalizedObject.put("@xmlns", namespace);
					innerNormalizedObject.put("$", field.getValue());
					normalizedJsonObject.put(localName, innerNormalizedObject);
				} else if (!namespacesByPrefix.containsKey(prefix)
						&& !XMLConstants.NULL_NS_URI.equals(currentNamespace)) {
					LinkedHashMap<String, Object> innerNormalizedObject = new LinkedHashMap<>();
					innerNormalizedObject.put("@xmlns", XMLConstants.NULL_NS_URI);
					innerNormalizedObject.put("$", field.getValue());
					normalizedJsonObject.put(localName, innerNormalizedObject);
				} else {
					normalizedJsonObject.put(localName, field.getValue());
				}
			}
		}
	}

	private static Pair<String, String> splitPrefixAndLocalName(String key) {
		int separatorLocation = key.indexOf(SEPARATOR);

		String prefix, localName;
		if (separatorLocation != -1) {
			prefix = key.substring(0, separatorLocation);
			localName = key.substring(separatorLocation + 1);
		} else {
			prefix = XMLConstants.DEFAULT_NS_PREFIX;
			localName = key;
		}

		return Pair.of(prefix, localName);
	}

    /*
     * The sole purpose of the CorrectedJsonStreamFactory class is to correct some weirdness in the
     * Staxon library where a default namespace ("@xmlns") would get turned into ("@xmlns:xmlns")
     * for example:
     * 
     * <some_element xmlns="some:uri" some_attribute="XML_1.0">
     * 
     * would turn into:
     * 
     * "some_element" : { "@xmlns:xmlns" : "some:uri", "@some_attribute" : "XML_1.0", ...
     * 
     * The problem with this is that Staxon is unable to turn the above JSON back into the original
     * XML. So this fixes that yielding:
     * 
     * "some_element" : { "@xmlns" : "some:uri", "@some_attribute" : "XML_1.0", ...
     */
    public static class CorrectedJsonStreamFactory extends JsonStreamFactory {
        static final String REPLACEE_TEXT = "@xmlns:xmlns";
        static final String REPLACEMENT_TEXT = "@xmlns";

        static class MyJsonStreamTarget implements JsonStreamTarget {
            final JsonStreamTarget delegate;

            public MyJsonStreamTarget(JsonStreamTarget delegate) {
                this.delegate = delegate;
            }

            @Override
            public void close() throws IOException {
                delegate.close();
            }

            @Override
            public void flush() throws IOException {
                delegate.flush();
            }

            @Override
            public void endArray() throws IOException {
                delegate.endArray();
            }

            @Override
            public void endObject() throws IOException {
                delegate.endObject();
            }

            @Override
            public void name(String name) throws IOException {
                delegate.name(REPLACEE_TEXT.equals(name) ? REPLACEMENT_TEXT : name);
            }

            @Override
            public void startArray() throws IOException {
                delegate.startArray();
            }

            @Override
            public void startObject() throws IOException {
                delegate.startObject();
            }

            @Override
            public void value(Object value) throws IOException {
                delegate.value(value);
            }
        }

        private final JsonStreamFactory delegate;

        public CorrectedJsonStreamFactory() {
            this(newFactory());
        }

        public CorrectedJsonStreamFactory(JsonStreamFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public JsonStreamSource createJsonStreamSource(InputStream input) throws IOException {
            return delegate.createJsonStreamSource(input);
        }

        @Override
        public JsonStreamSource createJsonStreamSource(Reader reader) throws IOException {
            return delegate.createJsonStreamSource(reader);
        }

        @Override
        public JsonStreamTarget createJsonStreamTarget(OutputStream output, boolean prettyPrint) throws IOException {
            return new MyJsonStreamTarget(delegate.createJsonStreamTarget(output, prettyPrint));
        }

        @Override
        public JsonStreamTarget createJsonStreamTarget(Writer writer, boolean prettyPrint) throws IOException {
            return new MyJsonStreamTarget(delegate.createJsonStreamTarget(writer, prettyPrint));
        }
    }
}
