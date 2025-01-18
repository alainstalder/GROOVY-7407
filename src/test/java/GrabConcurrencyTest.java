import ch.grengine.Grengine;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import groovy.grape.Grape;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovySystem;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.jupiter.api.Assertions.fail;

public class GrabConcurrencyTest {

    private final static int N_THREADS = 100;

    private final static String OLD_DEFAULT_GRAPE_CONFIG_TEMPLATE_FILENAME = "defaultGrapeConfig.template.xml";
    private final static String NEW_DEFAULT_GRAPE_CONFIG_TEMPLATE_FILENAME = "defaultGrapeConfigPlusArtifactLockNio.template.xml";

    // Tests the current Groovy default, Groovy 4.0.24 (or see build.gradle).
    @Test
    public void testParallelGrabs_oldDefaultGrapeConfig() throws Exception {
        final String testName = new Object(){}.getClass().getEnclosingMethod().getName();
        testParallelGrabs(testName, OLD_DEFAULT_GRAPE_CONFIG_TEMPLATE_FILENAME, false);
    }

    // Same as above, but additionally protects grabs with Grengine.Grape methods.
    @Test
    public void testParallelGrabs_oldDefaultGrapeConfigPlusGrengineLocking() throws Exception {
        final String testName = new Object(){}.getClass().getEnclosingMethod().getName();
        testParallelGrabs(testName, OLD_DEFAULT_GRAPE_CONFIG_TEMPLATE_FILENAME, true);
    }

    // Tests the proposed new Groovy default, MR 2142.
    @Test
    public void testParallelGrabs_newDefaultGrapeConfig() throws Exception {
        final String testName = new Object(){}.getClass().getEnclosingMethod().getName();
        testParallelGrabs(testName, NEW_DEFAULT_GRAPE_CONFIG_TEMPLATE_FILENAME, false);
    }

    // Same as above, but additionally protects grabs with Grengine.Grape methods.
    @Test
    public void testParallelGrabs_newDefaultGrapeConfigPlusGrengineLocking() throws Exception {
        final String testName = new Object(){}.getClass().getEnclosingMethod().getName();
        testParallelGrabs(testName, NEW_DEFAULT_GRAPE_CONFIG_TEMPLATE_FILENAME, true);
    }

    private void testParallelGrabs(final String testName, final String grapeConfigTemplateFilename, final boolean useGrengineLocking) throws Exception {
        final long t0 = System.currentTimeMillis();

        try {
            System.out.println("START " + testName);
            System.out.println("- Groovy  " + GroovySystem.getVersion());
            System.out.println("- Java    " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
            System.out.println("- OS      " + System.getProperty("os.name") + " (" + System.getProperty("os.version") + ")");

            if (useGrengineLocking) {
                Grengine.Grape.activate();
            }

            final File projectRoot = new File(".").getCanonicalFile();
            final File grapeRoot = new File(projectRoot, "test-grape-root");
            if (!grapeRoot.exists()) {
                fail("File '" + grapeRoot.getPath() + "' does not exist");
            }
            if (!grapeRoot.isDirectory()) {
                fail("File '" + grapeRoot.getPath() + "' is not a directory");
            }

            final File grapeConfigTemplateFile = new File(grapeRoot, grapeConfigTemplateFilename);

            final String grapeConfigTemplate = FileUtils.readFileToString(grapeConfigTemplateFile, UTF_8);
            final String grapeConfig = grapeConfigTemplate.replace("###PATH###", grapeRoot.getPath());
            //System.out.println(grapeConfig);

            final File grapeConfigFile = new File(grapeRoot, "grapeConfig.xml");
            FileUtils.writeStringToFile(grapeConfigFile, grapeConfig, UTF_8);

            final File grapeCache = new File(grapeRoot, "grapes");

            final File artifactDir = new File(grapeCache, "com.google.guava/guava/jars");

            // cleanup grapes
            if (grapeCache.exists() && grapeCache.isDirectory()) {
                FileUtils.deleteDirectory(grapeCache);
                assertThat(grapeCache.mkdir(), is(true));
            }

            System.setProperty("groovy.grape.report.downloads", "true");
            System.setProperty("grape.root", grapeRoot.getCanonicalPath());
            System.setProperty("grape.config", new File(grapeRoot, "grapeConfig.xml").getPath());

            final List<Thread> scriptThreads = new LinkedList<>();

            for (int i = 0; i < N_THREADS; i++) {
                final int ii = i;
                Thread scriptThread = new Thread(
                        () -> {
                            try {
                                GroovyClassLoader loader = new GroovyClassLoader();

                                final Map<String, Object> args = new HashMap<>();
                                final Map<String, Object> dependencies = new HashMap<>();

                                args.put("disableChecksums", "false");
                                args.put("classLoader", loader);
                                args.put("autoDownload", "true");

                                dependencies.put("group", "com.google.guava");
                                dependencies.put("module", "guava");
                                dependencies.put("version", "1" + (ii % 10) + ".0");

                                Object obj = Grape.getInstance().grab(args, dependencies);
                                if (obj != null) {
                                    String msg = "grab returned: " + obj;
                                    throw new RuntimeException(msg);
                                }
                                loader.loadClass("com.google.common.base.Ascii");

                                System.out.println("EXIT REGULAR: " + Thread.currentThread().getName());
                            } catch (Throwable t) {
                                System.out.println("EXIT THROW: " + Thread.currentThread().getName());
                                t.printStackTrace();
                            }

                        });
                scriptThread.setName(String.format("Grab%04d (guava 1%01d.0)", i, i % 10));
                scriptThreads.add(scriptThread);
            }

            for (Thread scriptThread : scriptThreads) {
                scriptThread.start();
            }

            boolean allThreadsDone;
            do {
                Thread.sleep(10000L);
                final List<String> sortedThreadNameList = Thread.getAllStackTraces().keySet().stream()
                        .map(Thread::getName)
                        .filter(n -> n.startsWith("Grab"))
                        .sorted(Comparator.comparing(String::toString))
                        .collect(Collectors.toList());
                System.out.println(new Date() + " - " + sortedThreadNameList.size() + " threads running...");
                for (String threadName : sortedThreadNameList) {
                    System.out.println("- " + threadName);
                }

                if (!artifactDir.exists()) {
                    System.out.println("Downloaded artifacts: none");
                } else {
                    final String[] artifacts = artifactDir.list();
                    if (artifacts == null) {
                        System.out.println("Downloaded artifacts: could not list");
                    } else {
                        List<String> artifactList = Arrays.asList(artifacts);
                        artifactList.sort(String::compareTo);
                        System.out.println("Downloaded artifacts:");
                        for (String artifact : artifactList) {
                            System.out.println("- " + artifact);
                        }
                    }
                }

                allThreadsDone = sortedThreadNameList.isEmpty();

            } while (!allThreadsDone);

        } finally {

            final long t1 = System.currentTimeMillis();
            System.out.println("Test took " + ((t1-t0)/1000L) + " seconds");

            if (useGrengineLocking) {
                Grengine.Grape.deactivate();
            }

            System.out.println("END " + testName);
        }
    }

}
