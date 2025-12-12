import java.io.*;
import java.util.*;

public class Main {
    static class Token {
        final Type type;
        final String text;
        final int line;
        final int col;
        Token(Type type, String text, int line, int col) {
            this.type = type;
            this.text = text;
            this.line = line;
            this.col = col;
        }
        public String toString() {
            return String.format("%s('%s')@%d:%d", type, text, line, col);
        }
    }

    enum Type {
        LBRACE, RBRACE, COLON, COMMA, EQ, LBRACK, RBRACK,
        NUMBER, IDENT,
        EOF
    }

    static class Lexer {
        private final String input;
        private final int length;
        private int pos = 0;
        private int line = 1;
        private int col = 1;

        Lexer(String input) {
            this.input = input;
            this.length = input.length();
        }

        Token next() {
            skipWhitespaceAndComments();
            if (pos >= length) return new Token(Type.EOF, "", line, col);
            char c = peek();
            int tokLine = line;
            int tokCol = col;
            switch (c) {
                case '{': advance(); return new Token(Type.LBRACE, "{", tokLine, tokCol);
                case '}': advance(); return new Token(Type.RBRACE, "}", tokLine, tokCol);
                case ':': advance(); return new Token(Type.COLON, ":", tokLine, tokCol);
                case ',': advance(); return new Token(Type.COMMA, ",", tokLine, tokCol);
                case '=': advance(); return new Token(Type.EQ, "=", tokLine, tokCol);
                case '[': advance(); return new Token(Type.LBRACK, "[", tokLine, tokCol);
                case ']': advance(); return new Token(Type.RBRACK, "]", tokLine, tokCol);
                default:
            }
            if (c == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
                int start = pos;
                advance();
                advance();
                while (pos < length && isHex(peek())) advance();
                String num = input.substring(start, pos);
                return new Token(Type.NUMBER, num, tokLine, tokCol);
            }
            if (isIdentStart(c)) {
                int start = pos;
                while (pos < length && isIdentPart(peek())) advance();
                String id = input.substring(start, pos);
                return new Token(Type.IDENT, id, tokLine, tokCol);
            }
            throw new ParseException(tokLine, tokCol, "Unexpected character: '" + c + "'");
        }

        private void skipWhitespaceAndComments() {
            while (pos < length) {
                char c = peek();
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    advance();
                    continue;
                }
                if (c == '#') {
                    if (peek(1) == '=') {
                        advance();
                        advance();
                        boolean found = false;
                        while (pos < length) {
                            if (peek() == '=' && peek(1) == '#') {
                                advance();
                                advance();
                                found = true;
                                break;
                            } else {
                                advance();
                            }
                        }
                        if (!found) throw new ParseException(line, col, "Unterminated multiline comment");
                        continue;
                    }
                    while (pos < length && peek() != '\n') advance();
                    continue;
                }
                break;
            }
        }

        private char peek() { return pos < length ? input.charAt(pos) : '\0'; }
        private char peek(int ahead) { int p = pos + ahead; return p < length ? input.charAt(p) : '\0'; }
        private void advance() {
            char c = input.charAt(pos++);
            if (c == '\n') { line++; col = 1; } else { col++; }
        }

        private boolean isHex(char c) { return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'); }
        private boolean isIdentStart(char c) { return (c == '_') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'); }
        private boolean isIdentPart(char c) {
            return isIdentStart(c) || (c >= '0' && c <= '9');
        }
    }

    static class ParseException extends RuntimeException {
        final int line;
        final int col;
        ParseException(int line, int col, String msg) {
            super(String.format("Parse error at %d:%d: %s", line, col, msg));
            this.line = line;
            this.col = col;
        }
    }

    interface Value { }

    static class NumberValue implements Value {
        final String lexeme;
        final long value;
        NumberValue(String lexeme) { this.lexeme = lexeme; this.value = parseHex(lexeme); }
        static long parseHex(String s) {
            return Long.parseUnsignedLong(s.substring(2), 16);
        }
    }

    static class DictValue implements Value {
        final Map<String, Value> map;
        DictValue(Map<String, Value> map) { this.map = map; }
    }

    static class ConstRef implements Value {
        final String name;
        ConstRef(String name) { this.name = name; }
    }

    static class Parser {
        private final Lexer lex;
        private Token cur;
        Parser(Lexer lex) { this.lex = lex; this.cur = lex.next(); }

        Map<String, Value> parseAll() {
            Map<String, Value> top = new LinkedHashMap<>();
            while (cur.type != Type.EOF) {
                if (cur.type == Type.IDENT) {
                    String name = cur.text;
                    eat(Type.IDENT);
                    expect(Type.EQ);
                    eat(Type.EQ);
                    Value v = parseValue();
                    top.put(name, v);
                } else {
                    throw new ParseException(cur.line, cur.col, "Expected identifier at top level");
                }
            }
            return top;
        }

        Value parseValue() {
            switch (cur.type) {
                case NUMBER: {
                    String lex = cur.text;
                    eat(Type.NUMBER);
                    return new NumberValue(lex);
                }
                case LBRACE: {
                    return parseDict();
                }
                case LBRACK: {
                    eat(Type.LBRACK);
                    if (cur.type != Type.IDENT) throw new ParseException(cur.line, cur.col, "Expected identifier inside []");
                    String name = cur.text;
                    eat(Type.IDENT);
                    expect(Type.RBRACK);
                    eat(Type.RBRACK);
                    return new ConstRef(name);
                }
                default:
                    throw new ParseException(cur.line, cur.col, "Unexpected token when parsing value: " + cur.type);
            }
        }

        DictValue parseDict() {
            eat(Type.LBRACE);
            Map<String, Value> map = new LinkedHashMap<>();

            if (cur.type != Type.RBRACE) {
                while (true) {
                    if (cur.type != Type.IDENT) throw new ParseException(cur.line, cur.col, "Expected identifier in dict");
                    String name = cur.text;
                    eat(Type.IDENT);
                    expect(Type.COLON);
                    eat(Type.COLON);
                    Value v = parseValue();
                    map.put(name, v);

                    if (cur.type == Type.COMMA) {
                        eat(Type.COMMA);
                        if (cur.type == Type.RBRACE) {
                            throw new ParseException(cur.line, cur.col, "Trailing comma in dict");
                        }
                        continue;
                    } else if (cur.type == Type.RBRACE) {
                        break;
                    } else {
                        throw new ParseException(cur.line, cur.col, "Expected ',' or '}' after dict entry");
                    }
                }
            }

            expect(Type.RBRACE);
            eat(Type.RBRACE);
            return new DictValue(map);
        }

        private void expect(Type t) { if (cur.type != t) throw new ParseException(cur.line, cur.col, "Expected " + t + " but got " + cur.type); }
        private void eat(Type t) { expect(t); cur = lex.next(); }
    }

    static class Evaluator {
        final Map<String, Value> env;
        final Map<String, Value> evaluated = new LinkedHashMap<>();
        Evaluator(Map<String, Value> env) { this.env = env; }

        Map<String, Value> evaluateAll() {
            for (Map.Entry<String, Value> entry : env.entrySet()) {
                evaluate(entry.getKey(), new HashSet<>());
            }
            return evaluated;
        }

        Value evaluate(String name, Set<String> stack) {
            if (evaluated.containsKey(name)) return evaluated.get(name);
            if (!env.containsKey(name)) throw new ParseException(0,0, "Unknown constant: " + name);
            if (stack.contains(name)) throw new ParseException(0,0, "Cyclic constant dependency detected: " + name);
            stack.add(name);
            Value raw = env.get(name);
            Value val = evalValue(raw, stack);
            evaluated.put(name, val);
            stack.remove(name);
            return val;
        }

        Value evalValue(Value v, Set<String> stack) {
            if (v instanceof NumberValue) return v;
            if (v instanceof ConstRef) {
                String ref = ((ConstRef)v).name;
                return evaluate(ref, stack);
            }
            if (v instanceof DictValue) {
                Map<String, Value> res = new LinkedHashMap<>();
                for (Map.Entry<String, Value> e : ((DictValue)v).map.entrySet()) {
                    res.put(e.getKey(), evalValue(e.getValue(), stack));
                }
                return new DictValue(res);
            }
            throw new IllegalStateException("Unknown Value type");
        }
    }

    static class XMLSerializer {
        private final StringBuilder out = new StringBuilder();
        void serializeAll(Map<String, Value> map) {
            out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            out.append("<config>\n");
            for (Map.Entry<String, Value> e : map.entrySet()) {
                writeElement(e.getKey(), e.getValue(), 1);
            }
            out.append("</config>\n");
        }

        private void writeElement(String name, Value v, int indent) {
            indent(indent);
            out.append("<").append(name).append(">\n");
            writeValue(v, indent+1);
            indent(indent);
            out.append("</").append(name).append(">\n");
        }

        private void writeValue(Value v, int indent) {
            if (v instanceof NumberValue) {
                indent(indent); out.append("<number>").append(((NumberValue)v).value).append("</number>\n"); return;
            }
            if (v instanceof DictValue) {
                indent(indent); out.append("<dict>\n");
                for (Map.Entry<String, Value> e : ((DictValue)v).map.entrySet()) {
                    indent(indent+1); out.append("<entry name=\"").append(escapeXml(e.getKey())).append("\">\n");
                    writeValue(e.getValue(), indent+2);
                    indent(indent+1); out.append("</entry>\n");
                }
                indent(indent); out.append("</dict>\n"); return;
            }
            throw new IllegalStateException("Cannot serialize value of unknown type");
        }

        private void indent(int n) { for (int i=0;i<n;i++) out.append("  "); }
        private String escapeXml(String s) { return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;"); }
        public String toString() { return out.toString(); }
    }

    static String readAllStdin() throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) { sb.append(line).append('\n'); }
        return sb.toString();
    }

    static String translate(String input) {
        Lexer lexer = new Lexer(input);
        Parser parser = new Parser(lexer);
        Map<String, Value> ast = parser.parseAll();
        Evaluator eval = new Evaluator(ast);
        Map<String, Value> evaluated = eval.evaluateAll();
        XMLSerializer ser = new XMLSerializer();
        ser.serializeAll(evaluated);
        return ser.toString();
    }

    public static void main(String[] args) {
        try {
            String input = readAllStdin();
            String xml = translate(input);
            System.out.print(xml);
        } catch (ParseException ex) {
            System.err.println(ex.getMessage());
            System.exit(2);
        } catch (IOException ex) {
            System.err.println("I/O error: " + ex.getMessage());
            System.exit(3);
        }
    }

    static final String EXAMPLE_NETWORK = """
#=
Многострочный комментарий
про конфигурацию сети
=#
server = {
  host: hostname,
  port: 0x1F90, # 8080 в hex
}
hostname = 127001
""";

    static final String EXAMPLE_APP = """
# Конфигурация приложения
db = {
  url: dburl,
  pool_size: 0x10,
}
dburl = 0x2A
cache = {
  size: [pool_size],
}
""";

    static void runTests() {
        System.out.println("Running quick tests...");
        try {
            String out1 = translate("""
name = 0x10
cfg = { a: [name], b: 0x2 }
""");
            System.out.println(out1);
        } catch (Exception e) { e.printStackTrace(); }

        try {
            String out2 = translate(EXAMPLE_NETWORK);
            System.out.println(out2);
        } catch (Exception e) { e.printStackTrace(); }

        try {
            String out3 = translate(EXAMPLE_APP);
            System.out.println(out3);
        } catch (Exception e) { e.printStackTrace(); }
    }
}