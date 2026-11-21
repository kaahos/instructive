import instructive.parse.Instruction;
import instructive.parse.x86.X86Disassembler;
import java.io.*;
import java.util.*;

/**
 * Compares X86Disassembler with reference lines "hexaddress<TAB>hexbytes<TAB>text" (or
 * {@code <invalid>}) and summarises the mismatches by mnemonic. Encodings the decoder
 * deliberately leaves out (APX, XOP, 3DNow!, PadLock) are counted separately.
 *
 * <p>Usage: java -cp parse.jar RefCheck.java REFERENCE [EXAMPLES [TOP [AGREED]]], where
 * AGREED receives the lines both decoders agree on.
 */
public class RefCheck {
    /** Encoding families that are deliberately not decoded, or null. */
    static String family(byte[] b) {
        int i = 0;
        while (i < b.length && (isPrefix(b[i] & 0xFF) || (b[i] & 0xF0) == 0x40)) i++;
        if (i >= b.length) return null;
        int op = b[i] & 0xFF;
        if (op == 0xD5) return "APX REX2";
        if (op == 0x62 && i + 3 < b.length && (((b[i + 1] & 0x08) != 0) || (b[i + 1] & 7) == 4 || (b[i + 2] & 0x04) == 0)) return "APX EVEX";
        if (op == 0x8F && i + 1 < b.length && (b[i + 1] & 0x1F) >= 8) return "XOP";
        if (op == 0x0F && i + 1 < b.length && (b[i + 1] & 0xFF) == 0x0F) return "3DNow";
        if (op == 0x0F && i + 1 < b.length && ((b[i + 1] & 0xFF) == 0xA6 || (b[i + 1] & 0xFF) == 0xA7)) return "PadLock";
        return null;
    }

    static final java.util.regex.Pattern APX = java.util.regex.Pattern.compile("\\br(1[6-9]|2[0-9]|3[01])[bwd]?\\b|\\{nf\\}|\\bc(cmp|test|fcmov)|\\b(push|pop)(2|p)\\b");

    static boolean isPrefix(int v) {
        return v == 0xF0 || v == 0xF2 || v == 0xF3 || v == 0x2E || v == 0x36 || v == 0x3E || v == 0x26 || v == 0x64 || v == 0x65 || v == 0x66 || v == 0x67;
    }

    public static void main(String[] args) throws IOException {
        X86Disassembler d = new X86Disassembler();
        Map<String, Integer> bad = new HashMap<>();
        Map<String, Integer> excluded = new TreeMap<>();
        Map<String, List<String>> examples = new HashMap<>();
        long total = 0, mismatches = 0;
        int perKey = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        PrintWriter agree = args.length > 3 ? new PrintWriter(new FileWriter(args[3])) : null;
        try (BufferedReader in = new BufferedReader(new FileReader(args[0]))) {
            for (String line; (line = in.readLine()) != null; ) {
                String[] f = line.split("\t");
                if (f.length < 3 || f[2].equals("<unknown>")) continue;
                total++;
                long address = Long.parseUnsignedLong(f[0], 16);
                byte[] bytes = HexFormat.of().parseHex(f[1]);
                if (f[2].equals("<invalid>")) {
                    Instruction i = d.decode(bytes, 0, address);
                    if (!i.isDecoded() && agree != null) agree.println(line);
                    if (i.isDecoded() && family(bytes) == null) {
                        mismatches++;
                        String key = "INVALID " + i.mnemonic();
                        bad.merge(key, 1, Integer::sum);
                        List<String> ex = examples.computeIfAbsent(key, k -> new ArrayList<>());
                        if (ex.size() < perKey) ex.add(f[1] + "\n      got:  " + i.text() + " [length " + i.length() + "]");
                    }
                    continue;
                }
                Instruction i;
                String got;
                try {
                    i = d.decode(bytes, 0, address);
                    got = i.length() == bytes.length ? i.text() : i.text() + " [length " + i.length() + "]";
                } catch (RuntimeException e) {
                    got = "EXCEPTION " + e;
                }
                if (got.equals(f[2]) && agree != null) agree.println(line);
                if (!got.equals(f[2])) {
                    String family = family(bytes);
                    if (family == null && APX.matcher(f[2]).find()) family = "APX (by text)";
                    if (family != null) {
                        excluded.merge(family, 1, Integer::sum);
                        continue;
                    }
                    mismatches++;
                    String[] words = f[2].split(" ");
                    String key = words[0] + ((words[0].equals("lock") || words[0].startsWith("rep") || words[0].startsWith("{") || words[0].equals("addr32") || words[0].equals("data16")) && words.length > 1 ? " " + words[1] : "");
                    bad.merge(key, 1, Integer::sum);
                    List<String> ex = examples.computeIfAbsent(key, k -> new ArrayList<>());
                    if (ex.size() < perKey) ex.add(f[1] + "\n      want: " + f[2] + "\n      got:  " + got);
                }
            }
        }
        if (agree != null) agree.close();
        System.out.println(mismatches + " mismatches of " + total + "; excluded " + excluded);
        bad.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(Integer.parseInt(args.length > 2 ? args[2] : "40"))
                .forEach(e -> { System.out.println(e.getValue() + "\t" + e.getKey()); examples.get(e.getKey()).forEach(x -> System.out.println("    " + x)); });
    }
}
