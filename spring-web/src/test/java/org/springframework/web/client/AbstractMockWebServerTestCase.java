package org.springframework.web.client;

import java.io.EOFException;
import java.nio.charset.Charset;
import java.util.Collections;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;

import org.springframework.http.MediaType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * @author Brian Clozel
 */
public class AbstractMockWebServerTestCase {

	protected static final String helloWorld = "H\u00e9llo W\u00f6rld";

  private static final Charset UTF_8 = Charset.forName("UTF-8");

  private static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");

	protected static final MediaType textContentType =
			new MediaType("text", "plain", Collections.singletonMap("charset", "UTF-8"));

	private MockWebServer server;

	protected int port;

	protected String baseUrl;

	@Before
	public void setUp() throws Exception {
		this.server = new MockWebServer();
		this.server.setDispatcher(new TestDispatcher());
		this.server.start();
		this.port = this.server.getPort();
		this.baseUrl = "http://localhost:" + this.port;
	}

	@After
	public void tearDown() throws Exception {
		this.server.shutdown();
	}

	protected class TestDispatcher extends Dispatcher {
		@Override
		public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
			try {
				byte[] helloWorldBytes = helloWorld.getBytes(UTF_8);

				if (request.getPath().equals("/get")) {
					return getRequest(request, helloWorldBytes, textContentType.toString());
				}
				else if (request.getPath().equals("/get/nothing")) {
					return getRequest(request, new byte[0], textContentType.toString());
				}
				else if (request.getPath().equals("/get/nocontenttype")) {
					return getRequest(request, helloWorldBytes, null);
				}
				else if (request.getPath().equals("/post")) {
					return postRequest(request, helloWorld, "/post/1", textContentType.toString(), helloWorldBytes);
				}
				else if (request.getPath().equals("/jsonpost")) {
					return jsonPostRequest(request, "/jsonpost/1", "application/json; charset=utf-8");
				}
				else if (request.getPath().equals("/status/nocontent")) {
					return new MockResponse().setResponseCode(204);
				}
				else if (request.getPath().equals("/status/notmodified")) {
					return new MockResponse().setResponseCode(304);
				}
				else if (request.getPath().equals("/status/notfound")) {
					return new MockResponse().setResponseCode(404);
				}
				else if (request.getPath().equals("/status/server")) {
					return new MockResponse().setResponseCode(500);
				}
				else if (request.getPath().contains("/uri/")) {
					return new MockResponse().setBody(request.getPath()).setHeader("Content-Type", "text/plain");
				}
				else if (request.getPath().equals("/multipart")) {
					return multipartRequest(request);
				}
				else if (request.getPath().equals("/form")) {
					return formRequest(request);
				}
				else if (request.getPath().equals("/delete")) {
					return new MockResponse().setResponseCode(200);
				}
				else if (request.getPath().equals("/patch")) {
					return patchRequest(request, helloWorld, textContentType.toString(), helloWorldBytes);
				}
				else if (request.getPath().equals("/put")) {
					return putRequest(request, helloWorld);
				}
				return new MockResponse().setResponseCode(404);
			}
			catch (Throwable exc) {
				return new MockResponse().setResponseCode(500).setBody(exc.toString());
			}
		}
	}


	private MockResponse getRequest(RecordedRequest request, byte[] body, String contentType) {
		if(request.getMethod().equals("OPTIONS")) {
			return new MockResponse().setResponseCode(200).setHeader("Allow", "GET, OPTIONS, HEAD, TRACE");
		}
		Buffer buf = new Buffer();
		buf.write(body);
		MockResponse response = new MockResponse()
				.setHeader("Content-Length", body.length)
				.setBody(buf)
				.setResponseCode(200);
		if (contentType != null) {
			response = response.setHeader("Content-Type", contentType);
		}
		return response;
	}

	private MockResponse postRequest(RecordedRequest request, String expectedRequestContent,
			String location, String contentType, byte[] responseBody) {

		assertTrue("Invalid request content-length",
				Integer.parseInt(request.getHeader("Content-Length")) > 0);
		String requestContentType = request.getHeader("Content-Type");
		assertNotNull("No content-type", requestContentType);
		Charset charset = ISO_8859_1;
		if(requestContentType.indexOf("charset=") > -1) {
			String charsetName = requestContentType.split("charset=")[1];
			charset = Charset.forName(charsetName);
		}
		assertEquals("Invalid request body", expectedRequestContent, request.getBody().readString(charset));
		Buffer buf = new Buffer();
		buf.write(responseBody);
		return new MockResponse()
				.setHeader("Location", baseUrl + location)
				.setHeader("Content-Type", contentType)
				.setHeader("Content-Length", responseBody.length)
				.setBody(buf)
				.setResponseCode(201);
	}

	private MockResponse jsonPostRequest(RecordedRequest request, String location, String contentType) {

		assertTrue("Invalid request content-length",
				Integer.parseInt(request.getHeader("Content-Length")) > 0);
		assertNotNull("No content-type", request.getHeader("Content-Type"));
		return new MockResponse()
				.setHeader("Location", baseUrl + location)
				.setHeader("Content-Type", contentType)
				.setHeader("Content-Length", request.getBody().size())
				.setBody(request.getBody())
				.setResponseCode(201);
	}

	private MockResponse multipartRequest(RecordedRequest request) {
		String contentType = request.getHeader("Content-Type");
		assertTrue(contentType.startsWith("multipart/form-data"));
		String boundary = contentType.split("boundary=")[1];
		Buffer body = request.getBody();
		try {
			assertPart(body, "form-data", boundary, "name 1", "text/plain", "value 1");
			assertPart(body, "form-data", boundary, "name 2", "text/plain", "value 2+1");
			assertPart(body, "form-data", boundary, "name 2", "text/plain", "value 2+2");
			assertFilePart(body, "form-data", boundary, "logo", "logo.jpg", "image/jpeg");
		}
		catch (EOFException e) {
			throw new RuntimeException(e);
		}
		return new MockResponse().setResponseCode(200);
	}

	private void assertPart(Buffer buffer, String disposition, String boundary, String name,
			String contentType, String value) throws EOFException {

		assertTrue(buffer.readUtf8Line().contains("--" + boundary));
		String line = buffer.readUtf8Line();
		assertTrue(line.contains("Content-Disposition: "+ disposition));
		assertTrue(line.contains("name=\""+ name + "\""));
		assertTrue(buffer.readUtf8Line().startsWith("Content-Type: "+contentType));
		assertTrue(buffer.readUtf8Line().equals("Content-Length: " + value.length()));
		assertTrue(buffer.readUtf8Line().equals(""));
		assertTrue(buffer.readUtf8Line().equals(value));
	}

	private void assertFilePart(Buffer buffer, String disposition, String boundary, String name,
			String filename, String contentType) throws EOFException {

		assertTrue(buffer.readUtf8Line().contains("--" + boundary));
		String line = buffer.readUtf8Line();
		assertTrue(line.contains("Content-Disposition: "+ disposition));
		assertTrue(line.contains("name=\""+ name + "\""));
		assertTrue(line.contains("filename=\""+ filename + "\""));
		assertTrue(buffer.readUtf8Line().startsWith("Content-Type: "+contentType));
		assertTrue(buffer.readUtf8Line().startsWith("Content-Length: "));
		assertTrue(buffer.readUtf8Line().equals(""));
		assertNotNull(buffer.readUtf8Line());
	}

	private MockResponse formRequest(RecordedRequest request) {
		assertEquals("application/x-www-form-urlencoded", request.getHeader("Content-Type"));
		String body = request.getBody().readUtf8();
		assertThat(body, Matchers.containsString("name+1=value+1"));
		assertThat(body, Matchers.containsString("name+2=value+2%2B1"));
		assertThat(body, Matchers.containsString("name+2=value+2%2B2"));
		return new MockResponse().setResponseCode(200);
	}

	private MockResponse patchRequest(RecordedRequest request, String expectedRequestContent,
			String contentType, byte[] responseBody) {
		assertEquals("PATCH", request.getMethod());
		assertTrue("Invalid request content-length",
				Integer.parseInt(request.getHeader("Content-Length")) > 0);
		String requestContentType = request.getHeader("Content-Type");
		assertNotNull("No content-type", requestContentType);
		Charset charset = ISO_8859_1;
		if(requestContentType.indexOf("charset=") > -1) {
			String charsetName = requestContentType.split("charset=")[1];
			charset = Charset.forName(charsetName);
		}
		assertEquals("Invalid request body", expectedRequestContent, request.getBody().readString(charset));
		Buffer buf = new Buffer();
		buf.write(responseBody);
		return new MockResponse().setResponseCode(201)
				.setHeader("Content-Length", responseBody.length)
				.setHeader("Content-Type", contentType)
				.setBody(buf);
	}

	private MockResponse putRequest(RecordedRequest request, String expectedRequestContent) {
		assertTrue("Invalid request content-length",
				Integer.parseInt(request.getHeader("Content-Length")) > 0);
		String requestContentType = request.getHeader("Content-Type");
		assertNotNull("No content-type", requestContentType);
		Charset charset = ISO_8859_1;
		if(requestContentType.indexOf("charset=") > -1) {
			String charsetName = requestContentType.split("charset=")[1];
			charset = Charset.forName(charsetName);
		}
		assertEquals("Invalid request body", expectedRequestContent, request.getBody().readString(charset));
		return new MockResponse().setResponseCode(202);
	}

}
