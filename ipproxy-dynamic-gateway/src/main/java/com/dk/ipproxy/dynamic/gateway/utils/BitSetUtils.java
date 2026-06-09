package com.dk.ipproxy.dynamic.gateway.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.BitSet;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class BitSetUtils {
    /**
     * 将指定的端口数组转换为 Base64 编码的字符串
     *
     * @param ports 端口数组
     * @return Base64 编码的字符串
     */
    public static String portsToBase64(int offset, int[] ports) {
        BitSet bitSet = new BitSet();
        // 设置指定的端口
        for (int port : ports) {
            bitSet.set(port - offset);
        }
        // 将 BitSet 转换为字节数组
        byte[] rawBytes = bitSet.toByteArray();

        // 压缩Base64字符串
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream(rawBytes.length);
        try (GZIPOutputStream gzipStream = new GZIPOutputStream(byteStream)) {
            gzipStream.write(rawBytes);
        } catch (Exception e) {
            // do nothing
            return null;
        }
        byte[] compressedBytes = byteStream.toByteArray();
        return Base64.getEncoder().encodeToString(compressedBytes);
    }

    /**
     * 从 Base64 编码的字符串中获取端口数组
     *
     * @param base64Encoded Base64 编码的字符串
     * @return 端口数组
     */
    public static int[] base64ToPorts(int offset, String base64Encoded) {
        // 将base64解码
        byte[] compressedBytes = Base64.getDecoder().decode(base64Encoded);

        // 使用zip解压缩
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        try (GZIPInputStream gzipStream = new GZIPInputStream(new ByteArrayInputStream(compressedBytes))) {
            byte[] buffer = new byte[10240];
            int len;
            while ((len = gzipStream.read(buffer)) > 0) {
                byteStream.write(buffer, 0, len);
            }
        } catch (Exception e) {
            return null;
        }
        byte[] bitSetBytes = byteStream.toByteArray();

        // 重建BitSet
        BitSet bitSet = BitSet.valueOf(bitSetBytes);

        // 遍历BitSet，将BitSet中的端口添加到结果数组中
        List<Integer> portList = new ArrayList<>();
        for (int i = bitSet.nextSetBit(0); i >= 0; i = bitSet.nextSetBit(i + 1)) {
            portList.add(i + offset);
        }
        return portList.stream().mapToInt(Integer::intValue).toArray();
    }
}
