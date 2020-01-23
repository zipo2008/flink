/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.docs.configuration;

import org.apache.flink.annotation.docs.Documentation;
import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.description.Formatter;
import org.apache.flink.configuration.description.HtmlFormatter;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.flink.docs.configuration.ConfigOptionsDocGenerator.COMMON_SECTION_FILE_NAME;
import static org.apache.flink.docs.configuration.ConfigOptionsDocGenerator.DEFAULT_PATH_PREFIX;
import static org.apache.flink.docs.configuration.ConfigOptionsDocGenerator.LOCATIONS;
import static org.apache.flink.docs.configuration.ConfigOptionsDocGenerator.extractConfigOptions;
import static org.apache.flink.docs.configuration.ConfigOptionsDocGenerator.processConfigOptions;
import static org.apache.flink.docs.configuration.ConfigOptionsDocGenerator.stringifyDefault;
import static org.apache.flink.docs.configuration.ConfigOptionsDocGenerator.typeToHtml;

/**
 * This test verifies that all {@link ConfigOption ConfigOptions} in the configured
 * {@link ConfigOptionsDocGenerator#LOCATIONS locations} are documented and well-defined (i.e. no 2 options exist for
 * the same key with different descriptions/default values), and that the documentation does not refer to non-existent
 * options.
 */
public class ConfigOptionsDocsCompletenessITCase {

	private static final Formatter htmlFormatter = new HtmlFormatter();

	@Test
	public void testCommonSectionCompleteness() throws IOException, ClassNotFoundException {
		Map<String, List<DocumentedOption>> documentedOptions = parseDocumentedCommonOptions();
		Map<String, List<ExistingOption>> existingOptions = findExistingOptions(
			optionWithMetaInfo -> optionWithMetaInfo.field.getAnnotation(Documentation.SectionOption.class) != null);

		assertExistingOptionsAreWellDefined(existingOptions);

		compareDocumentedAndExistingOptions(documentedOptions, existingOptions);
	}

	@Test
	public void testFullReferenceCompleteness() throws IOException, ClassNotFoundException {
		Map<String, List<DocumentedOption>> documentedOptions = parseDocumentedOptions();
		Map<String, List<ExistingOption>> existingOptions = findExistingOptions(ignored -> true);

		assertExistingOptionsAreWellDefined(existingOptions);

		compareDocumentedAndExistingOptions(documentedOptions, existingOptions);
	}

	private static void assertExistingOptionsAreWellDefined(Map<String, List<ExistingOption>> allOptions) {
		allOptions.values().stream()
			.forEach(options -> options.stream()
				.reduce((option1, option2) -> {
					if (option1.equals(option2)) {
						// we allow multiple instances of ConfigOptions with the same key if they are identical
						return option1;
					} else {
						// found a ConfigOption pair with the same key that aren't equal
						// we fail here outright as this is not a documentation-completeness problem
						if (!option1.defaultValue.equals(option2.defaultValue)) {
							String errorMessage = String.format(
								"Ambiguous option %s due to distinct default values (%s (in %s) vs %s (in %s)).",
								option1.key,
								option1.defaultValue,
								option1.containingClass.getSimpleName(),
								option2.defaultValue,
								option2.containingClass.getSimpleName());
							throw new AssertionError(errorMessage);
						} else {
							String errorMessage = String.format(
								"Ambiguous option %s due to distinct descriptions (%s vs %s).",
								option1.key,
								option1.containingClass.getSimpleName(),
								option2.containingClass.getSimpleName());
							throw new AssertionError(errorMessage);
						}
					}
				}));
	}

	private static void compareDocumentedAndExistingOptions(Map<String, List<DocumentedOption>> documentedOptions, Map<String, List<ExistingOption>> existingOptions) {
		final Collection<String> problems = new ArrayList<>(0);

		// first check that all existing options are properly documented
		existingOptions.forEach((key, supposedStates) -> {
			List<DocumentedOption> documentedState = documentedOptions.get(key);

			for (ExistingOption supposedState : supposedStates) {
				if (documentedState == null || documentedState.isEmpty()) {
					// option is not documented at all
					problems.add("Option " + supposedState.key + " in " + supposedState.containingClass + " is not documented.");
				} else {
					final Iterator<DocumentedOption> candidates = documentedState.iterator();

					boolean matchFound = false;
					while (candidates.hasNext() && !matchFound) {
						DocumentedOption candidate = candidates.next();
						if (supposedState.defaultValue.equals(candidate.defaultValue) && supposedState.description.equals(candidate.description)) {
							matchFound = true;
							candidates.remove();
						}
					}

					if (!matchFound) {
						problems.add(String.format(
							"Documentation of %s in %s is outdated. Expected: default=(%s) description=(%s).",
							supposedState.key,
							supposedState.containingClass.getSimpleName(),
							supposedState.defaultValue,
							supposedState.description));
					}
				}
			}
		});

		// documentation contains an option that no longer exists
		documentedOptions.values().stream().flatMap(Collection::stream)
			.forEach(documentedOption -> problems.add("Documented option " + documentedOption.key + " does not exist."));

		if (!problems.isEmpty()) {
			StringBuilder sb = new StringBuilder("Documentation is outdated, please regenerate it according to the" +
				" instructions in flink-docs/README.md.");
			sb.append(System.lineSeparator());
			sb.append("\tProblems:");
			for (String problem : problems) {
				sb.append(System.lineSeparator());
				sb.append("\t\t");
				sb.append(problem);
			}
			Assert.fail(sb.toString());
		}
	}

	private static Map<String, List<DocumentedOption>> parseDocumentedCommonOptions() throws IOException {
		Path commonSection = Paths.get(System.getProperty("rootDir"), "docs", "_includes", "generated", COMMON_SECTION_FILE_NAME);
		return parseDocumentedOptionsFromFile(commonSection).stream()
			.collect(Collectors.groupingBy(option -> option.key, Collectors.toList()));
	}

	private static Map<String, List<DocumentedOption>> parseDocumentedOptions() throws IOException {
		Path includeFolder = Paths.get(System.getProperty("rootDir"), "docs", "_includes", "generated").toAbsolutePath();
		return Files.list(includeFolder)
			.filter(path -> path.getFileName().toString().contains("configuration"))
			.flatMap(file -> {
				try {
					return parseDocumentedOptionsFromFile(file).stream();
				} catch (IOException ignored) {
					return Stream.empty();
				}
			})
			.collect(Collectors.groupingBy(option -> option.key, Collectors.toList()));
	}

	private static Collection<DocumentedOption> parseDocumentedOptionsFromFile(Path file) throws IOException {
		Document document = Jsoup.parse(file.toFile(), StandardCharsets.UTF_8.name());
		document.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
		document.outputSettings().prettyPrint(false);
		return document.getElementsByTag("table").stream()
			.map(element -> element.getElementsByTag("tbody").get(0))
			.flatMap(element -> element.getElementsByTag("tr").stream())
			.map(tableRow -> {
				// Use split to exclude document key tag.
				String key = tableRow.child(0).text().split(" ")[0];
				String defaultValue = tableRow.child(1).text();
				String typeValue = tableRow.child(2).text();
				String description = tableRow.child(3)
					.childNodes()
					.stream()
					.map(Object::toString)
					.collect(Collectors.joining());
				return new DocumentedOption(
					key,
					defaultValue,
					typeValue,
					description,
					file.getName(file.getNameCount() - 1));
			})
			.collect(Collectors.toList());
	}

	private static Map<String, List<ExistingOption>> findExistingOptions(Predicate<ConfigOptionsDocGenerator.OptionWithMetaInfo> predicate) throws IOException, ClassNotFoundException {
		final Collection<ExistingOption> existingOptions = new ArrayList<>();

		for (OptionsClassLocation location : LOCATIONS) {
			processConfigOptions(System.getProperty("rootDir"), location.getModule(), location.getPackage(), DEFAULT_PATH_PREFIX, optionsClass ->
				extractConfigOptions(optionsClass)
					.stream()
					.filter(predicate)
					.map(optionWithMetaInfo -> toExistingOption(optionWithMetaInfo, optionsClass))
					.forEach(existingOptions::add));
		}

		return existingOptions.stream().collect(Collectors.groupingBy(option -> option.key, Collectors.toList()));
	}

	private static ExistingOption toExistingOption(ConfigOptionsDocGenerator.OptionWithMetaInfo optionWithMetaInfo, Class<?> optionsClass) {
		String key = optionWithMetaInfo.option.key();
		String defaultValue = stringifyDefault(optionWithMetaInfo);
		String typeValue = typeToHtml(optionWithMetaInfo);
		String description = htmlFormatter.format(optionWithMetaInfo.option.description());
		return new ExistingOption(key, defaultValue, typeValue, description, optionsClass);
	}

	private static final class ExistingOption extends Option {

		private final Class<?> containingClass;

		private ExistingOption(
				String key,
				String defaultValue,
				String typeValue,
				String description,
				Class<?> containingClass) {
			super(key, defaultValue, typeValue, description);
			this.containingClass = containingClass;
		}
	}

	private static final class DocumentedOption extends Option {

		private final Path containingFile;

		private DocumentedOption(
				String key,
				String defaultValue,
				String typeValue,
				String description,
				Path containingFile) {
			super(key, defaultValue, typeValue, description);
			this.containingFile = containingFile;
		}
	}

	private abstract static class Option {
		protected final String key;
		protected final String defaultValue;
		protected final String typeValue;
		protected final String description;

		private Option(String key, String defaultValue, String typeValue, String description) {
			this.key = key;
			this.defaultValue = defaultValue;
			this.typeValue = typeValue;
			this.description = description;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (o == null || getClass() != o.getClass()) {
				return false;
			}
			Option option = (Option) o;
			return Objects.equals(key, option.key) &&
				Objects.equals(defaultValue, option.defaultValue) &&
				Objects.equals(typeValue, option.typeValue) &&
				Objects.equals(description, option.description);
		}

		@Override
		public int hashCode() {
			return Objects.hash(key, defaultValue, typeValue, description);
		}

		@Override
		public String toString() {
			return "Option{" +
				"key='" + key + '\'' +
				", defaultValue='" + defaultValue + '\'' +
				", typeValue='" + typeValue + '\'' +
				", description='" + description + '\'' +
				'}';
		}
	}
}
