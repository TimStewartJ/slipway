package dev.timstewart.slipway.clientgametest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The results of one client GameTest run: a JUnit-style XML report (for Gradle, CI and humans) and a JSON file with
 * every scenario's measurements and evidence paths (for validation.json). Both are rewritten after every scenario, so
 * a crash still leaves the results so far.
 */
final class Report {
	enum Status { PASSED, FAILED, NOT_RUN }

	static final class Result {
		final String name;
		Status status = Status.NOT_RUN;
		double seconds;
		String message;
		String stackTrace;
		final Map<String, Object> metrics = new LinkedHashMap<>();
		final List<String> notes = new ArrayList<>();
		final List<String> evidence = new ArrayList<>();

		Result(String name) {
			this.name = name;
		}

		void metric(String key, Object value) {
			this.metrics.put(key, value instanceof Double d ? Math.round(d * 10000.0) / 10000.0 : value);
			SlipwayClientGameTests.LOG.info("[{}] {} = {}", this.name, key, value);
		}

		void note(String format, Object... args) {
			String text = String.format(Locale.ROOT, format, args);
			this.notes.add(text);
			SlipwayClientGameTests.LOG.info("[{}] {}", this.name, text);
		}

		void evidence(Path path) {
			this.evidence.add(path.toAbsolutePath().normalize().toString());
		}
	}

	private final Path dir;
	private final String startedAt = Instant.now().toString();
	private final List<Result> results = new ArrayList<>();

	Report(Path dir) {
		this.dir = dir;
	}

	Path dir() {
		return this.dir;
	}

	Result start(String name) {
		Result result = new Result(name);
		this.results.add(result);
		return result;
	}

	List<Result> results() {
		return this.results;
	}

	long count(Status status) {
		return this.results.stream().filter(r -> r.status == status).count();
	}

	static String stackTrace(Throwable t) {
		StringWriter out = new StringWriter();
		t.printStackTrace(new PrintWriter(out));
		return out.toString();
	}

	void write() {
		try {
			Files.createDirectories(this.dir);
			Files.writeString(this.dir.resolve("TEST-slipway-client-gametest.xml"), this.junitXml(), StandardCharsets.UTF_8);
			Map<String, Object> json = new LinkedHashMap<>();
			json.put("startedAt", this.startedAt);
			json.put("writtenAt", Instant.now().toString());
			json.put("passed", this.count(Status.PASSED));
			json.put("failed", this.count(Status.FAILED));
			json.put("notRun", this.count(Status.NOT_RUN));
			List<Map<String, Object>> list = new ArrayList<>();
			for (Result r : this.results) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("scenario", r.name);
				m.put("status", r.status.name().toLowerCase(Locale.ROOT));
				m.put("seconds", Math.round(r.seconds * 10.0) / 10.0);
				if (r.message != null) {
					m.put("message", r.message);
				}
				m.put("metrics", r.metrics);
				m.put("notes", r.notes);
				m.put("evidence", r.evidence);
				list.add(m);
			}
			json.put("scenarios", list);
			Gson gson = new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create();
			Files.writeString(this.dir.resolve("results.json"), gson.toJson(json), StandardCharsets.UTF_8);
		} catch (IOException e) {
			SlipwayClientGameTests.LOG.error("Could not write the client GameTest report", e);
		}
	}

	private String junitXml() {
		double total = this.results.stream().mapToDouble(r -> r.seconds).sum();
		StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		xml.append(String.format(Locale.ROOT, "<testsuite name=\"slipway-client-gametest\" tests=\"%d\" failures=\"%d\" errors=\"0\" skipped=\"%d\" time=\"%.1f\" timestamp=\"%s\">\n",
			this.results.size(), this.count(Status.FAILED), this.count(Status.NOT_RUN), total, escape(this.startedAt)));
		for (Result r : this.results) {
			xml.append(String.format(Locale.ROOT, "  <testcase classname=\"dev.timstewart.slipway.clientgametest\" name=\"%s\" time=\"%.1f\">\n", escape(r.name), r.seconds));
			if (r.status == Status.FAILED) {
				xml.append("    <failure message=\"").append(escape(r.message == null ? "failed" : r.message)).append("\">")
					.append(escape(r.stackTrace == null ? "" : r.stackTrace)).append("</failure>\n");
			} else if (r.status == Status.NOT_RUN) {
				xml.append("    <skipped message=\"").append(escape(r.message == null ? "not run" : r.message)).append("\"/>\n");
			}
			StringBuilder out = new StringBuilder();
			r.metrics.forEach((k, v) -> out.append(k).append(" = ").append(v).append('\n'));
			r.notes.forEach(n -> out.append(n).append('\n'));
			r.evidence.forEach(e -> out.append("evidence: ").append(e).append('\n'));
			xml.append("    <system-out>").append(escape(out.toString())).append("</system-out>\n");
			xml.append("  </testcase>\n");
		}
		xml.append("</testsuite>\n");
		return xml.toString();
	}

	private static String escape(String text) {
		StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
				case '<' -> out.append("&lt;");
				case '>' -> out.append("&gt;");
				case '&' -> out.append("&amp;");
				case '"' -> out.append("&quot;");
				default -> {
					if (c >= 0x20 || c == '\n' || c == '\r' || c == '\t') {
						out.append(c);
					}
				}
			}
		}
		return out.toString();
	}
}
