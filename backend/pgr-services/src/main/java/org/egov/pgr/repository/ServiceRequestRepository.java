package org.egov.pgr.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.egov.tracer.model.ServiceCallException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Collections;
import java.util.Map;

@Repository
@Slf4j
public class ServiceRequestRepository {

	private ObjectMapper mapper;

	private RestTemplate restTemplate;


	@Autowired
	public ServiceRequestRepository(ObjectMapper mapper, RestTemplate restTemplate) {
		this.mapper = mapper;
		this.restTemplate = restTemplate;
	}


	public Object fetchResult(StringBuilder uri, Object request) {
		mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
		Object response = null;
		try {
			response = restTemplate.postForObject(uri.toString(), request, Map.class);
		}catch(HttpClientErrorException e) {
			log.error("External Service threw an Exception: ",e);
			throw new ServiceCallException(e.getResponseBodyAsString());
		}catch(Exception e) {
			log.error("Exception while fetching from searcher: ",e);
		}

		return response;
	}

	/**
	 * The DIGIT 3.0-capable transport, added beside {@link #fetchResult(StringBuilder, Object)}
	 * rather than replacing it: 16 of fetchResult's 34 call sites are MDMS, which is not moving,
	 * and 104 test methods across 10 files bind to its signature. Both methods live here until
	 * every dependency behind fetchResult has been flipped.
	 *
	 * <p>Four things fetchResult cannot do, each of which on its own makes it unusable against v3:
	 * <ul>
	 *   <li><b>A verb other than POST.</b> Every v3 read is a GET; an individual update is a PUT.</li>
	 *   <li><b>A read with no body.</b> v3 carries no RequestInfo on a read and a body makes the
	 *       request malformed, so a body supplied on a GET or a DELETE is dropped here.</li>
	 *   <li><b>Headers.</b> v3 answers 400 "Missing X-Tenant-Id header" without X-Tenant-ID, wants
	 *       X-User-ID on a write, and reads action roles only off a Bearer token. Build the map
	 *       with {@link org.egov.pgr.util.V3Headers}, which holds the per-dependency precedence
	 *       rules.</li>
	 *   <li><b>Failing.</b> fetchResult swallows every non-HttpClientErrorException into a null
	 *       return, so a 5xx or a dead connection reaches the caller as "empty result" - the exact
	 *       shape of failure this migration has to stop producing. Here a failed call throws, and
	 *       the thrown message carries v3's own error text: v3 reports failure as a bare JSON array
	 *       of {code,message,description,params} with no envelope, and on the 409 raised by an
	 *       invalid state transition that message IS the diagnosis.</li>
	 * </ul>
	 *
	 * <p>The URI is passed to RestTemplate already built and already encoded, so no URI-template
	 * expansion is applied to it - a query value containing a brace is a value, not a placeholder.
	 *
	 * @param method       the HTTP verb
	 * @param uri          the absolute, fully-built, already-encoded target URI
	 * @param body         the request body, or null; ignored (with a warning) on a GET or a DELETE
	 * @param headers      per-request headers; null and blank names or values are skipped
	 * @param responseType the type to bind the response body to
	 * @return the deserialised response body, which may be null only when the service returned none
	 * @throws ServiceCallException on any non-2xx status, connection failure or unreadable response;
	 *                              note it carries its text in {@code getError()}, not
	 *                              {@code getMessage()}, which is always null on that class
	 */
	public <T> T exchange(HttpMethod method, String uri, Object body,
						  Map<String, String> headers, Class<T> responseType) {

		HttpHeaders httpHeaders = new HttpHeaders();
		httpHeaders.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
		if (headers != null) {
			headers.forEach((name, value) -> {
				if (name != null && !name.trim().isEmpty() && value != null && !value.trim().isEmpty())
					httpHeaders.set(name, value);
			});
		}

		Object payload = body;
		if (HttpMethod.GET.equals(method) || HttpMethod.DELETE.equals(method)) {
			if (payload != null)
				log.warn("Dropping the request body on {} {}: a DIGIT 3.0 read carries no RequestInfo and a body makes the request malformed", method, uri);
			payload = null;
		}
		if (payload != null)
			httpHeaders.setContentType(MediaType.APPLICATION_JSON);

		HttpEntity<Object> entity = new HttpEntity<>(payload, httpHeaders);
		try {
			ResponseEntity<T> response = restTemplate.exchange(URI.create(uri), method, entity, responseType);
			return response.getBody();
		} catch (HttpStatusCodeException e) {
			String detail = describeV3Failure(e.getResponseBodyAsString());
			log.error("{} {} failed with {}: {}", method, uri, e.getStatusCode(), detail);
			throw new ServiceCallException(method + " " + uri + " -> " + e.getStatusCode() + " " + detail);
		} catch (RestClientException e) {
			// A connection failure, a read timeout, or a body the converters could not read. This
			// is the branch fetchResult turns into a null return; here it stays a failure.
			log.error("{} {} could not be completed: ", method, uri, e);
			throw new ServiceCallException(method + " " + uri + " could not be completed: " + e.getMessage());
		}
	}

	/**
	 * Renders a v3 error body for the exception message. v3 reports failure as a bare JSON array of
	 * {code,message,description,params} with no envelope; anything that is not that shape - an
	 * object, a proxy's HTML, an empty body - is passed through verbatim rather than guessed at.
	 */
	private String describeV3Failure(String responseBody) {
		if (responseBody == null || responseBody.trim().isEmpty())
			return "(no response body)";
		try {
			JsonNode root = mapper.readTree(responseBody);
			if (root != null && root.isArray() && root.size() > 0) {
				StringBuilder rendered = new StringBuilder();
				for (JsonNode error : root) {
					if (rendered.length() > 0)
						rendered.append("; ");
					rendered.append(textOf(error, "code")).append(": ").append(textOf(error, "message"));
					String description = textOf(error, "description");
					if (!description.isEmpty())
						rendered.append(" (").append(description).append(")");
				}
				return rendered.toString();
			}
		} catch (Exception e) {
			log.debug("Error body was not a v3 error array; passing it through verbatim: {}", e.toString());
		}
		return responseBody;
	}

	private String textOf(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? "" : value.asText();
	}
}
