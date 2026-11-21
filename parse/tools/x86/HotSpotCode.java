import instructive.agent.Sampler;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
import java.util.stream.*;
import java.util.zip.*;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Runs a mix of JDK workloads under the agent, then writes every live compiled method as
 * "address<TAB>tier<TAB>name<TAB>hex code". Run it under several flags (-XX:UseAVX=0..3,
 * -XX:TieredStopAtLevel=1, other GCs...) to vary the code HotSpot emits.
 *
 * <p>Usage: java --enable-native-access=ALL-UNNAMED -javaagent:agent.jar -cp agent.jar:parse.jar
 * HotSpotCode.java OUTPUT SECONDS
 */
public class HotSpotCode {
    static volatile long sink;
    static int[] ia = new int[4096]; static long[] la = new long[4096];
    static float[] fa = new float[4096]; static double[] da = new double[4096];
    static byte[] ba = new byte[4096]; static short[] sa = new short[4096]; static char[] ca = new char[4096];

    static void loops() {
        for (int i = 0; i < ia.length; i++) { ia[i] = ia[i] * 3 + (ia[i] >>> 2) ^ i; la[i] = la[i] * 7 + (la[i] >> 3) - i; }
        for (int i = 0; i < fa.length; i++) { fa[i] = fa[i] * 1.5f + (float) Math.sqrt(i); da[i] = Math.fma(da[i], 0.5, i) + Math.abs(da[i]); }
        for (int i = 0; i < ba.length; i++) { ba[i] = (byte) (ba[i] + i); sa[i] = (short) (sa[i] * 3); ca[i] = (char) (ca[i] | 0x41); }
        long s = 0; int mx = 0; double d = 0;
        for (int i = 0; i < ia.length; i++) { s += ia[i]; mx = Math.max(mx, ia[i]); d += da[i] * fa[i]; s += Long.bitCount(la[i]) + Integer.numberOfLeadingZeros(ia[i]) + Long.numberOfTrailingZeros(la[i]); }
        for (int i = 0; i < ia.length; i++) { s += Integer.reverse(ia[i]) + Long.reverseBytes(la[i]) + Integer.rotateLeft(ia[i], i) + (ia[i] / (i | 1)) + (la[i] % (i + 7)); }
        for (int i = 0; i < da.length; i++) { d += Math.sin(da[i]) + Math.log(i + 1) + Math.exp(-i) + Math.pow(da[i], 0.3) + Math.floor(da[i]) + Math.rint(fa[i]) + (long) da[i] + (int) fa[i]; }
        sink = s + mx + (long) d + Arrays.hashCode(ia) + Arrays.hashCode(ba) + Arrays.mismatch(ia, ia.clone());
    }

    public static void main(String[] args) throws Exception {
        Random r = new Random(42);
        for (int i = 0; i < ia.length; i++) { ia[i] = r.nextInt(); la[i] = r.nextLong(); fa[i] = r.nextFloat(); da[i] = r.nextDouble(); }
        Cipher aes = Cipher.getInstance("AES/CBC/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(new byte[16], "AES"), new javax.crypto.spec.IvParameterSpec(new byte[16]));
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        long end = System.nanoTime() + Long.parseLong(args[1]) * 1_000_000_000L;
        int round = 0;
        while (System.nanoTime() < end) {
            round++;
            loops();
            int[] copy = ia.clone(); Arrays.sort(copy); long[] lc = la.clone(); Arrays.sort(lc); double[] dc = da.clone(); Arrays.sort(dc); float[] fc = fa.clone(); Arrays.parallelSort(fc);
            sink += Arrays.binarySearch(copy, 5) + Arrays.stream(copy).filter(x -> x > 0).map(x -> x * 2).sum();
            Map<String, Integer> m = new HashMap<>(); TreeMap<Integer, String> t = new TreeMap<>(); ConcurrentHashMap<Long, Long> chm = new ConcurrentHashMap<>();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 2000; i++) { String k = "k" + i + ":" + (i * 31); m.merge(k, i, Integer::sum); t.put(i ^ 0x55, k); chm.compute((long) i % 97, (a, b) -> b == null ? 1 : b + 1); sb.append(k).append(',').append(i * 1.25); }
            String s = sb.toString();
            sink += s.indexOf("k1999") + s.split(",").length + s.toUpperCase().hashCode() + s.replace('k', 'q').lastIndexOf('q') + s.compareTo(s.toLowerCase()) + (s.equals(sb.toString()) ? 1 : 0);
            byte[] utf8 = s.getBytes(StandardCharsets.UTF_8); sink += new String(utf8, StandardCharsets.UTF_8).length() + new String(utf8, StandardCharsets.ISO_8859_1).length() + ("é" + s).getBytes(StandardCharsets.UTF_16).length;
            List<String> words = Arrays.asList(s.split(",")); sink += words.stream().map(String::length).collect(Collectors.groupingBy(x -> x % 5, Collectors.counting())).size();
            Matcher mt = Pattern.compile("k(\\d+):(\\d+)").matcher(s); while (mt.find()) sink += mt.group(1).length();
            BigInteger b = BigInteger.valueOf(round).pow(50).multiply(BigInteger.TWO.pow(300)).mod(BigInteger.valueOf(1_000_000_007L)); sink += b.intValue() + new BigInteger(200, r).modPow(BigInteger.valueOf(65537), new BigInteger(256, r).setBit(0)).bitLength();
            sink += new java.math.BigDecimal(round).divide(new java.math.BigDecimal(7), java.math.MathContext.DECIMAL128).scale();
            CRC32 crc = new CRC32(); crc.update(utf8); CRC32C crcc = new CRC32C(); crcc.update(utf8); Adler32 ad = new Adler32(); ad.update(utf8); sink += crc.getValue() + crcc.getValue() + ad.getValue();
            for (String alg : new String[] {"MD5", "SHA-1", "SHA-256", "SHA-512", "SHA3-256"}) sink += MessageDigest.getInstance(alg).digest(utf8)[0];
            sink += aes.update(Arrays.copyOf(utf8, 4096)).length; gcm.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(new byte[16], "AES"), new javax.crypto.spec.GCMParameterSpec(128, new byte[12 + round % 2])); sink += gcm.doFinal(utf8, 0, 1024).length;
            sink += Base64.getEncoder().encodeToString(utf8).length() + Base64.getDecoder().decode(Base64.getEncoder().encode(utf8)).length;
            AtomicLong al = new AtomicLong(); LongAdder adder = new LongAdder(); Object lock = new Object();
            List<Future<?>> fs = new ArrayList<>();
            for (int k = 0; k < 4; k++) fs.add(pool.submit(() -> { for (int i = 0; i < 20000; i++) { al.incrementAndGet(); al.compareAndSet(i, i + 1); adder.add(i); synchronized (lock) { sink += i; } } }));
            for (Future<?> f : fs) f.get();
            ByteArrayOutputStream bo = new ByteArrayOutputStream(); try (GZIPOutputStream gz = new GZIPOutputStream(bo)) { gz.write(utf8); } sink += bo.size();
            Object[] objs = {1, "a", 2.0, 'c', 3L, new int[1], null}; for (int i = 0; i < 5000; i++) { Object o = objs[i % objs.length]; if (o instanceof Integer x) sink += x; else if (o instanceof String x) sink += x.length(); else if (o != null) sink += o.hashCode(); }
            try { Object o = objs[round % objs.length]; sink += ((String) o).length(); } catch (ClassCastException | NullPointerException e) { sink++; }
            String fmt = String.format("%d %s %.3f %x", round, s.substring(0, 10), 3.14 * round, round); sink += fmt.length();
            List<Integer> li = new ArrayList<>(); for (int i = 0; i < 3000; i++) li.add(r.nextInt(1000)); Collections.sort(li); sink += li.get(5) + new ArrayDeque<>(li).pollLast() + new PriorityQueue<>(li).poll() + new LinkedList<>(li).size() + new HashSet<>(li).size();
        }
        pool.shutdown();
        try (PrintWriter out = new PrintWriter(new FileWriter(args[0]))) {
            Sampler.forEachMethod(p -> p.machineCode().ifPresent(code -> {
                StringBuilder hex = new StringBuilder(code.length * 2);
                for (byte x : code) hex.append(String.format("%02x", x));
                out.println(Long.toHexString(p.address()) + "\t" + p.compLevel() + "\t" + p.name() + "\t" + hex);
            }));
        }
    }
}
