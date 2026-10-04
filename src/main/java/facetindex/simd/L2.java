package facetindex.simd;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Squared L2 kernels over vectors that live in mapped files, using the incubating Vector API.
 *
 * <p>uint8 path: 8 bytes at a time are zero-extended to eight 32-bit lanes (one 256-bit AVX2
 * register on the mini PC's Zen 2 cores), subtracted, squared and accumulated in two independent
 * accumulators so the multiply-add chains overlap. Exact integer arithmetic, so the result equals
 * the scalar loop bit for bit (property-tested).
 */
public final class L2 {
  private L2() {}

  private static final VectorSpecies<Byte> B64 = ByteVector.SPECIES_64;
  private static final VectorSpecies<Integer> I256 = IntVector.SPECIES_256;
  private static final VectorSpecies<Float> FP = FloatVector.SPECIES_PREFERRED;
  private static final ValueLayout.OfFloat LE_FLOAT =
      ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

  /** Squared L2 between uint8 query {@code q} and the uint8 row at byte offset {@code off}. */
  public static int u8(byte[] q, MemorySegment seg, long off, int d) {
    IntVector acc0 = IntVector.zero(I256);
    IntVector acc1 = IntVector.zero(I256);
    int i = 0;
    for (; i <= d - 16; i += 16) {
      IntVector a0 = (IntVector) ByteVector.fromArray(B64, q, i).convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0);
      IntVector b0 =
          (IntVector)
              ByteVector.fromMemorySegment(B64, seg, off + i, ByteOrder.LITTLE_ENDIAN)
                  .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0);
      IntVector d0 = a0.sub(b0);
      acc0 = acc0.add(d0.mul(d0));
      IntVector a1 = (IntVector) ByteVector.fromArray(B64, q, i + 8).convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0);
      IntVector b1 =
          (IntVector)
              ByteVector.fromMemorySegment(B64, seg, off + i + 8, ByteOrder.LITTLE_ENDIAN)
                  .convertShape(VectorOperators.ZERO_EXTEND_B2I, I256, 0);
      IntVector d1 = a1.sub(b1);
      acc1 = acc1.add(d1.mul(d1));
    }
    int sum = acc0.add(acc1).reduceLanes(VectorOperators.ADD);
    for (; i < d; i++) {
      int diff = (q[i] & 0xFF) - (seg.get(ValueLayout.JAVA_BYTE, off + i) & 0xFF);
      sum += diff * diff;
    }
    return sum;
  }

  /**
   * Squared L2 between signed int8 query {@code q} and the signed int8 row at {@code off}: the same
   * kernel with sign extension, for vectors read straight from Lucene's {@code .vec} file (uint8
   * values stored as x xor 0x80).
   */
  public static int i8(byte[] q, MemorySegment seg, long off, int d) {
    IntVector acc0 = IntVector.zero(I256);
    IntVector acc1 = IntVector.zero(I256);
    int i = 0;
    for (; i <= d - 16; i += 16) {
      IntVector a0 = (IntVector) ByteVector.fromArray(B64, q, i).convertShape(VectorOperators.B2I, I256, 0);
      IntVector b0 =
          (IntVector) ByteVector.fromMemorySegment(B64, seg, off + i, ByteOrder.LITTLE_ENDIAN).convertShape(VectorOperators.B2I, I256, 0);
      IntVector d0 = a0.sub(b0);
      acc0 = acc0.add(d0.mul(d0));
      IntVector a1 = (IntVector) ByteVector.fromArray(B64, q, i + 8).convertShape(VectorOperators.B2I, I256, 0);
      IntVector b1 =
          (IntVector) ByteVector.fromMemorySegment(B64, seg, off + i + 8, ByteOrder.LITTLE_ENDIAN).convertShape(VectorOperators.B2I, I256, 0);
      IntVector d1 = a1.sub(b1);
      acc1 = acc1.add(d1.mul(d1));
    }
    int sum = acc0.add(acc1).reduceLanes(VectorOperators.ADD);
    for (; i < d; i++) {
      int diff = q[i] - seg.get(ValueLayout.JAVA_BYTE, off + i);
      sum += diff * diff;
    }
    return sum;
  }

  /** Scalar reference for {@link #u8}. */
  public static int u8Scalar(byte[] q, MemorySegment seg, long off, int d) {
    int sum = 0;
    for (int i = 0; i < d; i++) {
      int diff = (q[i] & 0xFF) - (seg.get(ValueLayout.JAVA_BYTE, off + i) & 0xFF);
      sum += diff * diff;
    }
    return sum;
  }

  /** Squared L2 between two uint8 heap arrays (scalar; used off the hot path). */
  public static int u8(byte[] a, byte[] b) {
    int sum = 0;
    for (int i = 0; i < a.length; i++) {
      int diff = (a[i] & 0xFF) - (b[i] & 0xFF);
      sum += diff * diff;
    }
    return sum;
  }

  /** Squared L2 between float query {@code q} and the float32 row at byte offset {@code off}. */
  public static float f32(float[] q, MemorySegment seg, long off, int d) {
    FloatVector acc0 = FloatVector.zero(FP);
    FloatVector acc1 = FloatVector.zero(FP);
    int lanes = FP.length();
    int i = 0;
    for (; i <= d - 2 * lanes; i += 2 * lanes) {
      FloatVector d0 = FloatVector.fromArray(FP, q, i).sub(FloatVector.fromMemorySegment(FP, seg, off + 4L * i, ByteOrder.LITTLE_ENDIAN));
      acc0 = d0.fma(d0, acc0);
      FloatVector d1 =
          FloatVector.fromArray(FP, q, i + lanes)
              .sub(FloatVector.fromMemorySegment(FP, seg, off + 4L * (i + lanes), ByteOrder.LITTLE_ENDIAN));
      acc1 = d1.fma(d1, acc1);
    }
    for (; i <= d - lanes; i += lanes) {
      FloatVector d0 = FloatVector.fromArray(FP, q, i).sub(FloatVector.fromMemorySegment(FP, seg, off + 4L * i, ByteOrder.LITTLE_ENDIAN));
      acc0 = d0.fma(d0, acc0);
    }
    float sum = acc0.add(acc1).reduceLanes(VectorOperators.ADD);
    for (; i < d; i++) {
      float diff = q[i] - seg.get(LE_FLOAT, off + 4L * i);
      sum += diff * diff;
    }
    return sum;
  }

  /** Squared L2 between a float query and a float centroid table row (heap). */
  public static float f32(float[] q, float[] table, int off, int d) {
    FloatVector acc = FloatVector.zero(FP);
    int lanes = FP.length();
    int i = 0;
    for (; i <= d - lanes; i += lanes) {
      FloatVector diff = FloatVector.fromArray(FP, q, i).sub(FloatVector.fromArray(FP, table, off + i));
      acc = diff.fma(diff, acc);
    }
    float sum = acc.reduceLanes(VectorOperators.ADD);
    for (; i < d; i++) {
      float diff = q[i] - table[off + i];
      sum += diff * diff;
    }
    return sum;
  }

  /** Squared L2 between rows of two float arrays at the given offsets. */
  public static float f32(float[] a, int ao, float[] b, int bo, int d) {
    FloatVector acc = FloatVector.zero(FP);
    int lanes = FP.length();
    int i = 0;
    for (; i <= d - lanes; i += lanes) {
      FloatVector diff = FloatVector.fromArray(FP, a, ao + i).sub(FloatVector.fromArray(FP, b, bo + i));
      acc = diff.fma(diff, acc);
    }
    float sum = acc.reduceLanes(VectorOperators.ADD);
    for (; i < d; i++) {
      float diff = a[ao + i] - b[bo + i];
      sum += diff * diff;
    }
    return sum;
  }

  /** Dot product of rows of two float arrays at the given offsets. */
  public static float dot(float[] a, int ao, float[] b, int bo, int d) {
    FloatVector acc0 = FloatVector.zero(FP);
    FloatVector acc1 = FloatVector.zero(FP);
    int lanes = FP.length();
    int i = 0;
    for (; i <= d - 2 * lanes; i += 2 * lanes) {
      acc0 = FloatVector.fromArray(FP, a, ao + i).fma(FloatVector.fromArray(FP, b, bo + i), acc0);
      acc1 = FloatVector.fromArray(FP, a, ao + i + lanes).fma(FloatVector.fromArray(FP, b, bo + i + lanes), acc1);
    }
    for (; i <= d - lanes; i += lanes) {
      acc0 = FloatVector.fromArray(FP, a, ao + i).fma(FloatVector.fromArray(FP, b, bo + i), acc0);
    }
    float sum = acc0.add(acc1).reduceLanes(VectorOperators.ADD);
    for (; i < d; i++) sum += a[ao + i] * b[bo + i];
    return sum;
  }

  /** Squared L2 between a uint8 row in a segment and a float centroid row (k-means assignment). */
  public static float u8ToF32(MemorySegment seg, long off, float[] table, int toff, int d) {
    float sum = 0f;
    for (int i = 0; i < d; i++) {
      float diff = (seg.get(ValueLayout.JAVA_BYTE, off + i) & 0xFF) - table[toff + i];
      sum += diff * diff;
    }
    return sum;
  }
}
