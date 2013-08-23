package eu.ehri.project.indexer;

import com.google.common.collect.Lists;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import org.codehaus.jackson.*;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.ObjectWriter;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.*;
import java.util.Iterator;
import java.util.List;

/**
 * @author Mike Bryant (http://github.com/mikesname)
 */
public class Indexer {

    private static ObjectMapper mapper = new ObjectMapper();
    private static ObjectWriter writer = mapper.writerWithDefaultPrettyPrinter();
    private static Client client = Client.create();

    private static class Stats {
        public int itemCount = 0;
    }

    public static void commit() {
        WebResource commitResource = client.resource
                ("http://localhost:8983/solr/portal/update?commit=true&optimize=true");
        ClientResponse response = commitResource
                .type(MediaType.APPLICATION_JSON)
                .post(ClientResponse.class);
        if (Response.Status.OK.getStatusCode() != response.getStatus()) {
            throw new RuntimeException("Error with Solr commit: " + response.getEntity(String.class));
        }
    }

    public static void doIndex(InputStream ios, boolean doCommit) {
        WebResource resource = client.resource
                ("http://localhost:8983/solr/portal/update?commit=" + doCommit);
        ClientResponse response = resource
                .type(MediaType.APPLICATION_JSON)
                .entity(ios)
                .post(ClientResponse.class);
        if (Response.Status.OK.getStatusCode() != response.getStatus()) {
            throw new RuntimeException("Error with Solr upload: " + response.getEntity(String.class));
        }
    }

    public static void printType(String type, OutputStream out, Stats stats) throws IOException{

        WebResource fetchResource = client.resource("http://localhost:7474/ehri/" + type + "/list?limit=100000");

        ClientResponse response = fetchResource.accept(MediaType.APPLICATION_JSON)
                .type(MediaType.APPLICATION_JSON)
                .get(ClientResponse.class);

        BufferedReader br = new BufferedReader(new InputStreamReader(response.getEntityInputStream()));

        JsonFactory f = new JsonFactory();
        JsonParser jp = f.createJsonParser(br);

        try {
            jp.nextToken();

            JsonGenerator generator = f.createJsonGenerator(out);
            try {
                generator.writeStartArray();
                generator.writeRaw('\n');
                while (jp.nextToken() == JsonToken.START_OBJECT) {
                    JsonNode node = mapper.readValue(jp, JsonNode.class);
                    convertItem(node, generator);
                    stats.itemCount++;
                }
                generator.writeEndArray();
                generator.writeRaw('\n');
            } finally {
                generator.flush();
                generator.close();
            }
        } finally {
            jp.close();
        }
        response.close();
    }

    /**
     * Convert a individual item from the stream and write the results.
     * @param node
     * @param generator
     * @throws IOException
     */
    private static void convertItem(JsonNode node, JsonGenerator generator) throws IOException {
        Iterator<JsonNode> elements = node.path("relationships").path("describes").getElements();
        List<JsonNode> descriptions = Lists.newArrayList(elements);

        if (descriptions.size() > 0) {
            for (JsonNode description : descriptions) {
                writer.writeValue(generator, JsonConverter.getDescribedData(description, node));
                generator.writeRaw('\n');
            }
        } else {
            writer.writeValue(generator, JsonConverter.getData(node));
            generator.writeRaw('\n');
        }
    }


    /**
     * Index a specific entity type.
     * @param type
     * @param temp
     * @throws IOException
     */
    public static void indexType(String type, File temp) throws IOException{

        System.out.println("Indexing: " + type);
        long startTime = System.nanoTime();
        Stats stats = new Stats();

        // Write the converted JSON to the temp file...
        OutputStream out = new FileOutputStream(temp);
        try {
            printType(type, out, stats);
        } finally {
             out.close();
        }

        // Load the temp file as an input stream and index it...
        InputStream ios = new FileInputStream(temp);
        try {
            doIndex(ios, false);
        } finally {
            ios.close();
        }

        long endTime = System.nanoTime();
        double duration = ((double)(endTime - startTime)) / 1000000000.0;

        System.out.println("Indexing completed in " + duration);
        System.out.println("Items indexed: " + stats.itemCount);
        System.out.println("Items per second: " + (stats.itemCount / duration));
    }

    public static void print(String[] types) throws IOException {
        OutputStream pw = new PrintStream(System.out);
        try {
            for (String type :  types) {
                printType(type, pw, new Stats());
            }
        } finally {
            pw.close();
        }
    }

    public static void index(String[] types) throws IOException {
        for (String type :  types) {
            File temp = File.createTempFile(type, "json");
            try {
                indexType(type, temp);
            } finally {
                temp.delete();
            }
        }
        commit();
    }

    public static void main(String[] args) throws IOException {
        index(args);
    }
}
