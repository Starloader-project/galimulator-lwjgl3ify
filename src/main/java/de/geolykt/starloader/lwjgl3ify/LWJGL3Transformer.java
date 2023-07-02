package de.geolykt.starloader.lwjgl3ify;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.LoggerFactory;

import me.coley.cafedude.classfile.ClassFile;
import me.coley.cafedude.classfile.ConstPool;
import me.coley.cafedude.classfile.Method;
import me.coley.cafedude.classfile.attribute.Attribute;
import me.coley.cafedude.classfile.attribute.CodeAttribute;
import me.coley.cafedude.classfile.constant.ConstPoolEntry;
import me.coley.cafedude.classfile.constant.CpClass;
import me.coley.cafedude.classfile.constant.CpFieldRef;
import me.coley.cafedude.classfile.constant.CpMethodRef;
import me.coley.cafedude.classfile.constant.CpNameType;
import me.coley.cafedude.classfile.constant.CpUtf8;
import me.coley.cafedude.classfile.instruction.Instruction;
import me.coley.cafedude.classfile.instruction.IntOperandInstruction;
import me.coley.cafedude.classfile.instruction.Opcodes;
import me.coley.cafedude.io.ClassFileReader;
import me.coley.cafedude.io.ClassFileWriter;
import me.coley.cafedude.io.InstructionReader;
import me.coley.cafedude.io.InstructionWriter;
import software.coley.llzip.ZipIO;
import software.coley.llzip.format.compression.ZipCompressions;
import software.coley.llzip.format.model.CentralDirectoryFileHeader;
import software.coley.llzip.format.model.LocalFileHeader;
import software.coley.llzip.format.model.PartType;
import software.coley.llzip.format.model.ZipArchive;
import software.coley.llzip.format.model.ZipPart;
import software.coley.llzip.format.write.JavaZipWriterStrategy;
import software.coley.llzip.format.write.ZipWriterStrategy;
import software.coley.llzip.util.BufferData;
import software.coley.llzip.util.ByteData;
import software.coley.llzip.util.ByteDataUtil;

public class LWJGL3Transformer {

    private static final String HELPER3 = "de/geolykt/starloader/lwjgl3ify/Helper";
    private static final String APPCFG3 = "com/badlogic/gdx/backends/lwjgl3/Lwjgl3ApplicationConfiguration";

    private static final List<String> FORBID_LIST = Arrays.asList("META-INF/maven/com.badlogicgames.gdx/gdx-backend-lwjgl/",
            "com/badlogic/gdx/backends/lwjgl/",
            "org/lwjgl/",
            "OpenAL32.dll",
            "OpenAL64.dll",
            "lwjgl.dll",
            "lwjgl64.dll",
            "liblwjgl.so",
            "liblwjgl64.so",
            "libopenal.so",
            "libopenal64.so",
            "liblwjgl.dylib",
            "openal.dylib");

    private static final Map<String, String> DIRECT_MAPPINGS = new HashMap<>();

    public static void invoke(@NotNull Path source, @NotNull Path target) {
        ZipArchive archive;
        try {
            archive = ZipIO.readJvm(source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        Iterator<ZipPart> parts = archive.getParts().iterator();
        while (parts.hasNext()) {
            ZipPart part = parts.next();
            if (part.type() != PartType.LOCAL_FILE_HEADER) {
                continue;
            }
            LocalFileHeader header = (LocalFileHeader) part;
            String name = header.getFileNameAsString();
            for (String list : FORBID_LIST) {
                if (name.contains(list)) {
                    parts.remove();
                }
            }

            if (name.contains("com/example/Main")
                    || name.equals("snoddasmannen/galimulator/")) {
                 try {
                    ByteData data = ZipCompressions.decompress(header);
                    data = LWJGL3Transformer.transformClassBytes(data);
                    if (data != null) {
                        header.setFileData(data);
                        header.setCompressionMethod(ZipCompressions.STORED);
                        // Other values (such as the CRC or the compressed/decompressed size) are not needed right now due to the implementation of the JavaZipWriterStrategy.
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }

        Iterator<CentralDirectoryFileHeader> headers = archive.getCentralDirectories().iterator();
        while (headers.hasNext()) {
            CentralDirectoryFileHeader header = headers.next();
            String name = header.getFileNameAsString();
            for (String list : FORBID_LIST) {
                if (name.contains(list)) {
                    headers.remove();
                }
            }
        }

        ZipWriterStrategy writeStrategy = new JavaZipWriterStrategy();

        try {
            writeStrategy.writeToDisk(archive, target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Nullable
    private static ByteData transformClassBytes(ByteData data) {
        if (data.length() < 1) {
            return null;
        }
        byte[] bytes = ByteDataUtil.toByteArray(data);
        try {
            ClassFile file = new ClassFileReader().read(bytes);
            boolean transformed = transformClass(file);
            if (!transformed) {
                return null;
            }
            return BufferData.wrap(new ClassFileWriter().write(file));
        } catch (Throwable t) {
            LoggerFactory.getLogger(LWJGL3Transformer.class).warn("Unable to transform bytes", t);
            return null;
        }
    }

    private static boolean transformClass(ClassFile file) {
        boolean transformed = false;
        ConstPool pool = file.getPool();
        StringBuilder sharedBuilder = new StringBuilder();
        for (int i = 0; i < pool.size(); i++) {
            ConstPoolEntry entry = pool.get(i);
            if (entry instanceof CpUtf8) {
                CpUtf8 utf = (CpUtf8) entry;
                String in = utf.getText();
                if (in.length() == 0) {
                    continue;
                }
                String mapping;
                if (in.codePointAt(0) == '(') {
                    sharedBuilder.setLength(0);
                    sharedBuilder.appendCodePoint('(');
                    if (!LWJGL3Transformer.remapSignature(sharedBuilder, in, 1, in.length())) {
                        continue;
                    }
                    mapping = sharedBuilder.toString();
                } else if (in.codePointBefore(in.length()) == ';') {
                    mapping = LWJGL3Transformer.remapSingleDesc(in, sharedBuilder);
                    if (mapping == in) {
                        continue;
                    }
                } else {
                    mapping = DIRECT_MAPPINGS.get(in);
                    if (mapping == null) {
                        continue;
                    }
                }
                utf.setText(mapping);
                transformed = true;
            }
        }
        InstructionReader insnReader = new InstructionReader();
        InstructionWriter insnWriter = new InstructionWriter();
        for (Method method : file.getMethods()) {
            for (Attribute attr : method.getAttributes()) {
                if (!(attr instanceof CodeAttribute)) {
                    continue;
                }
                CodeAttribute code = (CodeAttribute) attr;
                List<Instruction> instructions = insnReader.read(code);
                boolean modified = false;
                int insnLen = instructions.size();
                for (int i = 0 ; i < insnLen; i++) {
                    Instruction insn = instructions.get(i);
                    if (insn.getOpcode() == Opcodes.PUTFIELD) {
                        CpFieldRef fieldRef = (CpFieldRef) pool.get(((IntOperandInstruction) insn).getOperand());
                        String cname = pool.getUtf(((CpClass) pool.get(fieldRef.getClassIndex())).getIndex());
                        if (cname.equals(APPCFG3)) {
                            CpNameType nameType = (CpNameType) pool.get(fieldRef.getNameTypeIndex());
                            String name = pool.getUtf(nameType.getNameIndex());
                            String type = pool.getUtf(nameType.getTypeIndex());
                            String mname = "set" + ((char) Character.toUpperCase(name.codePointAt(0))) + name.substring(1);
                            String mtype; 
                            int opcode;
                            int clazz;
                            if (mname.equals("setWidth") || mname.equals("setHeight")) {
                                opcode = Opcodes.INVOKESTATIC;
                                mtype = "(L" + APPCFG3 + ";" + type + ")V";
                                clazz = writeClass(pool, writeUTF8(pool, HELPER3));
                            } else if (mname.equals("setForegroundFPS")) {
                                // The method doesn't exist in libGDX 1.9.11, so we need to use the closest matching thing.
                                mname = "setIdleFPS";
                                opcode = Opcodes.INVOKEVIRTUAL;
                                mtype = "(" + type + ")V";
                                clazz = fieldRef.getClassIndex();
                            } else {
                                opcode = Opcodes.INVOKEVIRTUAL;
                                mtype = "(" + type + ")V";
                                clazz = fieldRef.getClassIndex();
                            }
                            int methodIndex = writeMethodRef(pool,
                                    clazz,
                                    writeNameType(pool,
                                            writeUTF8(pool, mname),
                                            writeUTF8(pool, mtype)));
                            instructions.set(i, new IntOperandInstruction(opcode, methodIndex));
                            modified = true;
                        }
                    } else if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                        CpMethodRef methodRef = (CpMethodRef) pool.get(((IntOperandInstruction) insn).getOperand());
                        String cname = pool.getUtf(((CpClass) pool.get(methodRef.getClassIndex())).getIndex());
                        if (cname.equals(APPCFG3)) {
                            CpNameType nameType = (CpNameType) pool.get(methodRef.getNameTypeIndex());
                            String name = pool.getUtf(nameType.getNameIndex());
                            if (name.equals("addIcon")) {
                                instructions.set(i, new IntOperandInstruction(Opcodes.INVOKESTATIC,
                                        writeMethodRef(pool, writeClass(pool, writeUTF8(pool, HELPER3)),
                                                writeNameType(pool, nameType.getNameIndex(), writeUTF8(pool, "(L" + APPCFG3 + ";Ljava/lang/String;Lcom/badlogic/gdx/Files$FileType;)V")))));
                                modified = true;
                            }
                        }
                    }
                }
                if (modified) {
                    code.setCode(insnWriter.writeCode(instructions));
                    transformed = true;
                }
            }
        }
        return transformed;
    }

    private static int writeClass(ConstPool pool, int str) {
        int len = pool.size();
        for (int i = 0; i < len; i++) {
            ConstPoolEntry e = pool.get(i);
            if (e instanceof CpClass && ((CpClass) e).getIndex() == str) {
                return i;
            }
        }
        pool.add(new CpClass(str));
        return len + 1;
    }

    private static int writeNameType(ConstPool pool, int name, int desc) {
        int len = pool.size();
        for (int i = 0; i < len; i++) {
            ConstPoolEntry e = pool.get(i);
            if (e instanceof CpNameType) {
                CpNameType mref = (CpNameType) e;
                if (mref.getNameIndex() == name && mref.getTypeIndex() == desc) {
                    return i;
                }
            }
        }
        pool.add(new CpNameType(name, desc));
        return len + 1;
    }

    private static int writeMethodRef(ConstPool pool, int clazz, int nameType) {
        int len = pool.size();
        for (int i = 0; i < len; i++) {
            ConstPoolEntry e = pool.get(i);
            if (e instanceof CpMethodRef) {
                CpMethodRef mref = (CpMethodRef) e;
                if (mref.getClassIndex() == clazz && mref.getNameTypeIndex() == nameType) {
                    return i;
                }
            }
        }
        pool.add(new CpMethodRef(clazz, nameType));
        return len + 1;
    }

    private static int writeUTF8(ConstPool pool, String str) {
        int len = pool.size();
        for (int i = 0; i < len; i++) {
            ConstPoolEntry e = pool.get(i);
            if (e instanceof CpUtf8 && ((CpUtf8) e).getText().equals(str)) {
                return i;
            }
        }
        pool.add(new CpUtf8(str));
        return len + 1;
    }

    private static boolean remapSignature(StringBuilder signatureOut, String signature, int start, int end) {
        if (start == end) {
            return false;
        }
        int type = signature.codePointAt(start++);
        switch (type) {
        case 'T':
            // generics type parameter
            // fall-through intended as they are similar enough in format compared to objects
        case 'L':
            // object
            // find the end of the internal name of the object
            int endObject = start;
            while(true) {
                // this will skip a character, but this is not interesting as class names have to be at least 1 character long
                int codepoint = signature.codePointAt(++endObject);
                if (codepoint == ';') {
                    String name = signature.substring(start, endObject);
                    String newName = DIRECT_MAPPINGS.get(name);
                    boolean modified = false;
                    if (newName != null) {
                        name = newName;
                        modified = true;
                    }
                    signatureOut.appendCodePoint(type);
                    signatureOut.append(name);
                    signatureOut.append(';');
                    modified |= remapSignature(signatureOut, signature, ++endObject, end);
                    return modified;
                } else if (codepoint == '<') {
                    // generics - please no
                    // post scriptum: well, that was a bit easier than expected
                    int openingBrackets = 1;
                    int endGenerics = endObject;
                    while(true) {
                        codepoint = signature.codePointAt(++endGenerics);
                        if (codepoint == '>' ) {
                            if (--openingBrackets == 0) {
                                break;
                            }
                        } else if (codepoint == '<') {
                            openingBrackets++;
                        }
                    }
                    String name = signature.substring(start, endObject);
                    String newName = DIRECT_MAPPINGS.get(name);
                    boolean modified = false;
                    if (newName != null) {
                        name = newName;
                        modified = true;
                    }
                    signatureOut.append('L');
                    signatureOut.append(name);
                    signatureOut.append('<');
                    modified |= remapSignature(signatureOut, signature, endObject + 1, endGenerics++);
                    signatureOut.append('>');
                    // apparently that can be rarely be a '.', don't ask when or why exactly this occours
                    signatureOut.appendCodePoint(signature.codePointAt(endGenerics));
                    modified |= remapSignature(signatureOut, signature, ++endGenerics, end);
                    return modified;
                }
            }
        case '+':
            // idk what this one does - but it appears that it works good just like it does right now
        case '*':
            // wildcard - this can also be read like a regular primitive
            // fall-through intended
        case '(':
        case ')':
            // apparently our method does not break even in these cases, so we will consider them raw primitives
        case '[':
            // array - fall through intended as in this case they behave the same
        default:
            // primitive
            signatureOut.appendCodePoint(type);
            return remapSignature(signatureOut, signature, start, end); // Did not modify the signature - but following operations could
        }
    }

    private static String remapSingleDesc(String input, StringBuilder sharedBuilder) {
        int indexofL = input.indexOf('L');
        if (indexofL == -1) {
            return input;
        }
        int length = input.length();
        String internalName = input.substring(indexofL + 1, length - 1);
        String newInternalName = DIRECT_MAPPINGS.get(internalName);
        if (newInternalName == null) {
            return input;
        }
        sharedBuilder.setLength(indexofL + 1);
        sharedBuilder.setCharAt(indexofL, 'L');
        while(indexofL != 0) {
            sharedBuilder.setCharAt(--indexofL, '[');
        }
        sharedBuilder.append(newInternalName);
        sharedBuilder.append(';');
        return sharedBuilder.toString();
    }

    static {
        DIRECT_MAPPINGS.put("com/badlogic/gdx/backends/lwjgl/LwjglApplication", "com/badlogic/gdx/backends/lwjgl3/Lwjgl3Application");
        DIRECT_MAPPINGS.put("com/badlogic/gdx/backends/lwjgl/LwjglApplicationConfiguration", "com/badlogic/gdx/backends/lwjgl3/Lwjgl3ApplicationConfiguration");
    }
}
