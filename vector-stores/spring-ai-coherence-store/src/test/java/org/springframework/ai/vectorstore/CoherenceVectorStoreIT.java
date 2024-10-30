package org.springframework.ai.vectorstore;

import com.oracle.bedrock.junit.CoherenceClusterExtension;
import com.oracle.bedrock.runtime.coherence.CoherenceClusterMember;
import com.oracle.bedrock.runtime.coherence.options.ClusterName;
import com.oracle.bedrock.runtime.coherence.options.LocalHost;
import com.oracle.bedrock.runtime.coherence.options.RoleName;
import com.oracle.bedrock.runtime.coherence.options.WellKnownAddress;
import com.oracle.bedrock.runtime.java.options.IPv4Preferred;
import com.oracle.bedrock.runtime.java.options.SystemProperty;
import com.oracle.bedrock.runtime.options.DisplayName;
import com.oracle.bedrock.testsupport.junit.TestLogsExtension;

import com.tangosol.net.Coherence;
import com.tangosol.net.Session;

import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

public class CoherenceVectorStoreIT {

	@RegisterExtension
	static TestLogsExtension testLogs = new TestLogsExtension();

	@RegisterExtension
	static CoherenceClusterExtension cluster = new CoherenceClusterExtension()
		.with(ClusterName.of("CoherenceVectorStoreIT"), WellKnownAddress.loopback(), LocalHost.only(),
				IPv4Preferred.autoDetect(), SystemProperty.of("coherence.serializer", "pof"))
		.include(3, CoherenceClusterMember.class, DisplayName.of("storage"), RoleName.of("storage"), testLogs);

	final List<Document> documents = List.of(
			new Document(getText("classpath:/test/data/spring.ai.txt"), Map.of("meta1", "meta1")),
			new Document(getText("classpath:/test/data/time.shelter.txt")),
			new Document(getText("classpath:/test/data/great.depression.txt"), Map.of("meta2", "meta2")));

	public static String getText(final String uri) {
		try {
			return new DefaultResourceLoader().getResource(uri).getContentAsString(StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(TestClient.class)
		.withPropertyValues("test.spring.ai.vectorstore.coherence.distanceType=COSINE",
				"test.spring.ai.vectorstore.coherence.indexType=NONE");

	@SpringBootConfiguration
	@EnableAutoConfiguration
	public static class TestClient {

		@Value("${test.spring.ai.vectorstore.coherence.distanceType}")
		CoherenceVectorStore.DistanceType distanceType;

		@Value("${test.spring.ai.vectorstore.coherence.indexType}")
		CoherenceVectorStore.IndexType indexType;

		@Bean
		public VectorStore vectorStore(EmbeddingModel embeddingModel, Session session) {
			return new CoherenceVectorStore(embeddingModel, session).setDistanceType(distanceType)
				.setIndexType(indexType)
				.setForcedNormalization(distanceType == CoherenceVectorStore.DistanceType.COSINE
						|| distanceType == CoherenceVectorStore.DistanceType.IP);
		}

		@Bean
		public Session session(Coherence coherence) {
			return coherence.getSession();
		}

		@Bean
		public Coherence coherence() {
			return Coherence.clusterMember().start().join();
		}

		@Bean
		public EmbeddingModel embeddingModel() {
			try {
				TransformersEmbeddingModel tem = new TransformersEmbeddingModel();
				tem.afterPropertiesSet();
				return tem;
			}
			catch (Exception e) {
				throw new RuntimeException("Failed initializing embedding model", e);
			}
		}

	}

	private static void truncateMap(ApplicationContext context, String mapName) {
		Session session = context.getBean(Session.class);
		session.getMap(mapName).truncate();
	}

	public static Stream<Arguments> distanceAndIndex() {
		List<Arguments> argumentList = new ArrayList<>();
		for (var distanceType : CoherenceVectorStore.DistanceType.values()) {
			for (var indexType : CoherenceVectorStore.IndexType.values()) {
				argumentList.add(Arguments.of(distanceType, indexType));
			}
		}

		return argumentList.stream();
	}

	@ParameterizedTest(name = "Distance {0}, Index {1} : {displayName}")
	@MethodSource("distanceAndIndex")
	public void addAndSearch(CoherenceVectorStore.DistanceType distanceType, CoherenceVectorStore.IndexType indexType) {
		contextRunner.withPropertyValues("test.spring.ai.vectorstore.coherence.distanceType=" + distanceType)
			.withPropertyValues("test.spring.ai.vectorstore.coherence.indexType=" + indexType)
			.run(context -> {

				VectorStore vectorStore = context.getBean(VectorStore.class);

				vectorStore.add(documents);

				List<Document> results = vectorStore
					.similaritySearch(SearchRequest.query("What is Great Depression").withTopK(1));

				assertThat(results).hasSize(1);
				Document resultDoc = results.get(0);
				assertThat(resultDoc.getId()).isEqualTo(documents.get(2).getId());
				assertThat(resultDoc.getMetadata()).containsKeys("meta2", "distance");

				// Remove all documents from the store
				vectorStore.delete(documents.stream().map(doc -> doc.getId()).toList());

				List<Document> results2 = vectorStore
					.similaritySearch(SearchRequest.query("Great Depression").withTopK(1));
				assertThat(results2).hasSize(0);

				truncateMap(context, ((CoherenceVectorStore) vectorStore).getMapName());
			});
	}

	@ParameterizedTest(name = "Distance {0}, Index {1} : {displayName}")
	@MethodSource("distanceAndIndex")
	public void searchWithFilters(CoherenceVectorStore.DistanceType distanceType,
			CoherenceVectorStore.IndexType indexType) {
		contextRunner.withPropertyValues("test.spring.ai.vectorstore.coherence.distanceType=" + distanceType)
			.withPropertyValues("test.spring.ai.vectorstore.coherence.indexType=" + indexType)
			.run(context -> {

				VectorStore vectorStore = context.getBean(VectorStore.class);

				var bgDocument = new Document("The World is Big and Salvation Lurks Around the Corner",
						Map.of("country", "BG", "year", 2020, "foo bar 1", "bar.foo"));
				var nlDocument = new Document("The World is Big and Salvation Lurks Around the Corner",
						Map.of("country", "NL"));
				var bgDocument2 = new Document("The World is Big and Salvation Lurks Around the Corner",
						Map.of("country", "BG", "year", 2023));

				vectorStore.add(List.of(bgDocument, nlDocument, bgDocument2));

				SearchRequest searchRequest = SearchRequest.query("The World").withTopK(5).withSimilarityThresholdAll();

				List<Document> results = vectorStore.similaritySearch(searchRequest);

				assertThat(results).hasSize(3);

				results = vectorStore.similaritySearch(searchRequest.withFilterExpression("country == 'NL'"));

				assertThat(results).hasSize(1);
				assertThat(results.get(0).getId()).isEqualTo(nlDocument.getId());

				results = vectorStore.similaritySearch(searchRequest.withFilterExpression("country == 'BG'"));

				assertThat(results).hasSize(2);
				assertThat(results.get(0).getId()).isIn(bgDocument.getId(), bgDocument2.getId());
				assertThat(results.get(1).getId()).isIn(bgDocument.getId(), bgDocument2.getId());

				results = vectorStore
					.similaritySearch(searchRequest.withFilterExpression("country == 'BG' && year == 2020"));

				assertThat(results).hasSize(1);
				assertThat(results.get(0).getId()).isEqualTo(bgDocument.getId());

				results = vectorStore.similaritySearch(
						searchRequest.withFilterExpression("(country == 'BG' && year == 2020) || (country == 'NL')"));

				assertThat(results).hasSize(2);
				assertThat(results.get(0).getId()).isIn(bgDocument.getId(), nlDocument.getId());
				assertThat(results.get(1).getId()).isIn(bgDocument.getId(), nlDocument.getId());

				results = vectorStore.similaritySearch(searchRequest
					.withFilterExpression("NOT((country == 'BG' && year == 2020) || (country == 'NL'))"));

				assertThat(results).hasSize(1);
				assertThat(results.get(0).getId()).isEqualTo(bgDocument2.getId());

				try {
					vectorStore.similaritySearch(searchRequest.withFilterExpression("country == NL"));
					Assert.fail("Invalid filter expression should have been cached!");
				}
				catch (FilterExpressionTextParser.FilterExpressionParseException e) {
					assertThat(e.getMessage()).contains("Line: 1:17, Error: no viable alternative at input 'NL'");
				}

				// Remove all documents from the store
				truncateMap(context, ((CoherenceVectorStore) vectorStore).getMapName());
			});
	}

	@Test
	public void documentUpdate() {
		contextRunner.run(context -> {
			VectorStore vectorStore = context.getBean(VectorStore.class);

			Document document = new Document(UUID.randomUUID().toString(), "Spring AI rocks!!",
					Collections.singletonMap("meta1", "meta1"));

			vectorStore.add(List.of(document));

			List<Document> results = vectorStore.similaritySearch(SearchRequest.query("Spring").withTopK(5));

			assertThat(results).hasSize(1);
			Document resultDoc = results.get(0);
			assertThat(resultDoc.getId()).isEqualTo(document.getId());

			assertThat(resultDoc.getContent()).isEqualTo("Spring AI rocks!!");
			assertThat(resultDoc.getMetadata()).containsKeys("meta1", "distance");

			Document sameIdDocument = new Document(document.getId(),
					"The World is Big and Salvation Lurks Around the Corner",
					Collections.singletonMap("meta2", "meta2"));

			vectorStore.add(List.of(sameIdDocument));

			results = vectorStore.similaritySearch(SearchRequest.query("FooBar").withTopK(5));
			assertThat(results).hasSize(1);
			resultDoc = results.get(0);
			assertThat(resultDoc.getId()).isEqualTo(document.getId());
			assertThat(resultDoc.getContent()).isEqualTo("The World is Big and Salvation Lurks Around the Corner");
			assertThat(resultDoc.getMetadata()).containsKeys("meta2", "distance");

			truncateMap(context, ((CoherenceVectorStore) vectorStore).getMapName());
		});
	}

	@Test
	public void searchWithThreshold() {
		contextRunner.run(context -> {

			VectorStore vectorStore = context.getBean(VectorStore.class);

			vectorStore.add(documents);

			List<Document> fullResult = vectorStore
				.similaritySearch(SearchRequest.query("Time Shelter").withTopK(5).withSimilarityThresholdAll());

			assertThat(fullResult).hasSize(3);

			assertThat(isSortedByDistance(fullResult)).isTrue();

			List<Double> distances = fullResult.stream()
				.map(doc -> (Double) doc.getMetadata().get("distance"))
				.toList();

			double threshold = 1d - (distances.get(0) + distances.get(1)) / 2f;

			List<Document> results = vectorStore
				.similaritySearch(SearchRequest.query("Time Shelter").withTopK(5).withSimilarityThreshold(threshold));

			assertThat(results).hasSize(1);
			Document resultDoc = results.get(0);
			assertThat(resultDoc.getId()).isEqualTo(documents.get(1).getId());

			truncateMap(context, ((CoherenceVectorStore) vectorStore).getMapName());
		});
	}

	private static boolean isSortedByDistance(final List<Document> documents) {
		final List<Double> distances = documents.stream()
			.map(doc -> (Double) doc.getMetadata().get("distance"))
			.toList();

		if (CollectionUtils.isEmpty(distances) || distances.size() == 1) {
			return true;
		}

		Iterator<Double> iter = distances.iterator();
		Double current;
		Double previous = iter.next();
		while (iter.hasNext()) {
			current = iter.next();
			if (previous > current) {
				return false;
			}
			previous = current;
		}
		return true;
	}

}
