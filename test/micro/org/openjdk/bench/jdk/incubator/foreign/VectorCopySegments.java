package org.openjdk.bench.jdk.incubator.foreign;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;
import jdk.incubator.foreign.CLinker;
import jdk.incubator.foreign.MemoryAddress;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.ResourceScope;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.Vector;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@Warmup(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@State(org.openjdk.jmh.annotations.Scope.Thread)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 3, jvmArgsAppend = { "--add-modules=jdk.incubator.foreign,jdk.incubator.vector", "-Dforeign.restricted=permit",
    "--enable-native-access", "ALL-UNNAMED"})
public class VectorCopySegments {
  private static final VectorSpecies<Byte> BYTE_VECTOR_SPECIES = VectorSpecies.ofLargestShape(byte.class);
  private static final int lanes = BYTE_VECTOR_SPECIES.vectorByteSize();

  @Param({"1024", "1048576"})
  private int size;

  private MemoryAddress srcAddress;
  private MemoryAddress dstAddress;

  private ResourceScope sharedScope = ResourceScope.newSharedScope();

  private MemorySegment srcSegmentShared;
  private MemorySegment dstSegmentShared;

  private ByteBuffer srcBuffer;
  private ByteBuffer dstBuffer;

  private ByteBuffer srcDirect;
  private ByteBuffer dstDirect;

  private byte[] dstArray;

  private final static VectorShuffle<Byte> byteSwap;

  private static final int[] shuffleArr;

  private static final int UNROLL = 16;

  static {
    shuffleArr = new int[BYTE_VECTOR_SPECIES.length()];
    for (int i = 0; i < shuffleArr.length; i++) {
      shuffleArr[i] = (i / 4) * 4 + 4 - (i % 4) - 1;
    }

    byteSwap = VectorShuffle.fromArray(BYTE_VECTOR_SPECIES, shuffleArr, 0);
  }

  @Setup
  public void setup() {
    srcAddress = CLinker.allocateMemory(size);
    dstAddress = CLinker.allocateMemory(size);

    srcSegmentShared = srcAddress.asSegment(size, sharedScope);
    dstSegmentShared = dstAddress.asSegment(size, sharedScope);

    srcBuffer = srcSegmentShared.asByteBuffer();
    dstBuffer = dstSegmentShared.asByteBuffer();

    srcDirect = ByteBuffer.allocateDirect(size);
    dstDirect = ByteBuffer.allocateDirect(size);

    dstArray = new byte[size];
  }

  @Benchmark
  public void copyWithVector() {
    try (final var scope = ResourceScope.newConfinedScope()) {
      final var src = srcAddress.asSegment(size, scope).asByteBuffer();
      final var dst = dstAddress.asSegment(size, scope).asByteBuffer();

      final var lanes = BYTE_VECTOR_SPECIES.length();
      final var bound = BYTE_VECTOR_SPECIES.loopBound(size);

      for (int i=0; i < bound; i += lanes) {
        final var srcVector = ByteVector.fromByteBuffer(BYTE_VECTOR_SPECIES, src, i, ByteOrder.nativeOrder());

        srcVector.intoByteBuffer(dst, i, ByteOrder.nativeOrder());
      }
    }
  }

  @Benchmark
//  @CompilerControl(CompilerControl.Mode.PRINT)
  public void copyWithVectorDirectBuffer() {
    final var src = srcDirect;
    final var dst = dstDirect;

    final var lanes = BYTE_VECTOR_SPECIES.length();
    final var bound = BYTE_VECTOR_SPECIES.loopBound(src.limit());

    final var limit = bound - BYTE_VECTOR_SPECIES.vectorByteSize();

    // To optimize compilaton better
    if (limit + BYTE_VECTOR_SPECIES.vectorByteSize() > src.limit() || limit + BYTE_VECTOR_SPECIES.vectorByteSize() > dst.limit()) {
      throw new IndexOutOfBoundsException();
    }

    int i;
    for (i = 0; i < limit; i += BYTE_VECTOR_SPECIES.vectorByteSize()) {
      final var srcVector = ByteVector
          .fromByteBuffer(BYTE_VECTOR_SPECIES, src, i, ByteOrder.nativeOrder());

      srcVector.intoByteBuffer(dst, i, ByteOrder.nativeOrder());
    }
    final var srcVector = ByteVector
        .fromByteBuffer(BYTE_VECTOR_SPECIES, src, i, ByteOrder.nativeOrder());

    srcVector.intoByteBuffer(dst, i, ByteOrder.nativeOrder());
  }

  @Benchmark
//  @CompilerControl(CompilerControl.Mode.PRINT)
  public void copyWithVectorUnroller() {
    try (final var scope = ResourceScope.newConfinedScope()) {
      final var src = srcAddress.asSegment(size, scope).asByteBuffer();
      final var dst = dstAddress.asSegment(size, scope).asByteBuffer();

      final var bound = BYTE_VECTOR_SPECIES.loopBound(size);

      final var unrollBound = bound & ~(UNROLL - 1);

      var i = 0;

      // Loop unrolling
//      final var v = ByteVector.fromByteBuffer(BYTE_VECTOR_SPECIES, src, i + 0 * lanes, ByteOrder.nativeOrder());
//      v.intoByteBuffer(dst, i + 0 * lanes, ByteOrder.nativeOrder());
//
//      final var srcLimit = src.limit() - (15 * lanes + BYTE_VECTOR_SPECIES.vectorByteSize() - 1);
//      final var dstLimit = dst.limit() - (15 * lanes + BYTE_VECTOR_SPECIES.vectorByteSize() - 1);

      for (; i < unrollBound; i += lanes * UNROLL) {
//        final var limit = i + 15 * lanes + BYTE_VECTOR_SPECIES.vectorByteSize() - 1;
//        if (limit < src.limit() && limit < dst.limit()) {
          final var v1 = ByteVector
              .fromByteBuffer(BYTE_VECTOR_SPECIES, src, i + 0 * lanes, ByteOrder.nativeOrder());
          final var v2 = ByteVector
              .fromByteBuffer(BYTE_VECTOR_SPECIES, src, i + 1 * lanes, ByteOrder.nativeOrder());
          final var v3 = ByteVector
              .fromByteBuffer(BYTE_VECTOR_SPECIES, src, i + 2 * lanes, ByteOrder.nativeOrder());
          final var v4 = ByteVector
              .fromByteBuffer(BYTE_VECTOR_SPECIES, src, i + 3 * lanes, ByteOrder.nativeOrder());
          final var v5 = ByteVector
              .fromByteBuffer(BYTE_VECTOR_SPECIES, src, i + 4 * lanes, ByteOrder.nativeOrder());
          final var v6 = ByteVector
              .fromByteBuffer(BYTE_VECTOR_SPECIES, src, i + 5 * lanes, ByteOrder.nativeOrder());
          final var v7 = ByteVector
              .fromByteBuffer(BYTE_VECTOR_SPECIES, src, i + 6 * lanes, ByteOrder.nativeOrder());
          final var v8 = ByteVector
              .fromByteBuffer(BYTE_VECTOR_SPECIES, src, i + 7 * lanes, ByteOrder.nativeOrder());
          final var v9 = ByteVector
              .fromByteBuffer(BYTE_VECTOR_SPECIES, src, i + 8 * lanes, ByteOrder.nativeOrder());
          final var v10 = ByteVector
              .fromByteBuffer(BYTE_VECTOR_SPECIES, src, i + 9 * lanes, ByteOrder.nativeOrder());
          final var v11 = ByteVector
              .fromByteBuffer(BYTE_VECTOR_SPECIES, src, i + 10 * lanes, ByteOrder.nativeOrder());
          final var v12 = ByteVector
              .fromByteBuffer(BYTE_VECTOR_SPECIES, src, i + 11 * lanes, ByteOrder.nativeOrder());
          final var v13 = ByteVector
              .fromByteBuffer(BYTE_VECTOR_SPECIES, src, i + 12 * lanes, ByteOrder.nativeOrder());
          final var v14 = ByteVector
              .fromByteBuffer(BYTE_VECTOR_SPECIES, src, i + 13 * lanes, ByteOrder.nativeOrder());
          final var v15 = ByteVector
              .fromByteBuffer(BYTE_VECTOR_SPECIES, src, i + 14 * lanes, ByteOrder.nativeOrder());
          final var v16 = ByteVector
              .fromByteBuffer(BYTE_VECTOR_SPECIES, src, i + 15 * lanes, ByteOrder.nativeOrder());

          v1.intoByteBuffer(dst, i + 0 * lanes, ByteOrder.nativeOrder());
          v2.intoByteBuffer(dst, i + 1 * lanes, ByteOrder.nativeOrder());
          v3.intoByteBuffer(dst, i + 2 * lanes, ByteOrder.nativeOrder());
          v4.intoByteBuffer(dst, i + 3 * lanes, ByteOrder.nativeOrder());
          v5.intoByteBuffer(dst, i + 4 * lanes, ByteOrder.nativeOrder());
          v6.intoByteBuffer(dst, i + 5 * lanes, ByteOrder.nativeOrder());
          v7.intoByteBuffer(dst, i + 6 * lanes, ByteOrder.nativeOrder());
          v8.intoByteBuffer(dst, i + 7 * lanes, ByteOrder.nativeOrder());
          v9.intoByteBuffer(dst, i + 8 * lanes, ByteOrder.nativeOrder());
          v10.intoByteBuffer(dst, i + 9 * lanes, ByteOrder.nativeOrder());
          v11.intoByteBuffer(dst, i + 10 * lanes, ByteOrder.nativeOrder());
          v12.intoByteBuffer(dst, i + 11 * lanes, ByteOrder.nativeOrder());
          v13.intoByteBuffer(dst, i + 12 * lanes, ByteOrder.nativeOrder());
          v14.intoByteBuffer(dst, i + 13 * lanes, ByteOrder.nativeOrder());
          v15.intoByteBuffer(dst, i + 14 * lanes, ByteOrder.nativeOrder());
          v16.intoByteBuffer(dst, i + 15 * lanes, ByteOrder.nativeOrder());
//        } else {
//          throw new AssertionError("should not reach " + limit + " " + src.limit() + " " + dst.limit());
//        }
      }
    }
  }

  @Benchmark
//  @CompilerControl(CompilerControl.Mode.PRINT)
  public void copyWithVectorShuffle() {
    final var byteSwapVector = byteSwap.toVector();
    try (final var scope = ResourceScope.newConfinedScope()) {
      final var src = srcAddress.asSegment(size, scope).asByteBuffer();
      final var dst = dstAddress.asSegment(size, scope).asByteBuffer();

      final var lanes = BYTE_VECTOR_SPECIES.length();
      final var bound = BYTE_VECTOR_SPECIES.loopBound(size);

      for (int i=0; i < bound; i += lanes) {
        final var srcVector =  ByteVector.fromByteBuffer(BYTE_VECTOR_SPECIES, src, i, ByteOrder.nativeOrder());
        final var dstVector = byteSwapVector.selectFrom(srcVector);

        dstVector.intoByteBuffer(dst, i, ByteOrder.nativeOrder());
      }
    }
  }

  @Benchmark
  public void copyWithVectorToArray() {
    try (final var scope = ResourceScope.newConfinedScope()) {
      final var src = srcAddress.asSegment(size, scope).asByteBuffer();

      final var lanes = BYTE_VECTOR_SPECIES.length();
      final var bound = BYTE_VECTOR_SPECIES.loopBound(size);

      final var dstArray = this.dstArray;

      for (int i=0; i < bound; i += lanes) {
        final var srcVector = ByteVector.fromByteBuffer(BYTE_VECTOR_SPECIES, src, i, ByteOrder.nativeOrder());

        srcVector.intoArray(dstArray, i);
      }
    }
  }

  @Benchmark
  public void copyWithVectorShared() {
    final var lanes = BYTE_VECTOR_SPECIES.length();
    final var bound = BYTE_VECTOR_SPECIES.loopBound(size);

    final var srcBuffer = this.srcBuffer;

    for (int i=0; i < bound; i += lanes) {
      final var srcVector = ByteVector.fromByteBuffer(BYTE_VECTOR_SPECIES, srcBuffer, i, ByteOrder.nativeOrder());

      srcVector.intoByteBuffer(dstBuffer, i, ByteOrder.nativeOrder());
    }
  }

  @Benchmark
  public void copyWithNative() {
    try(final var scope = ResourceScope.newConfinedScope()) {
      final var src = srcAddress.asSegment(size, scope);
      final var dst = dstAddress.asSegment(size, scope);

      dst.copyFrom(src);
    }
  }

  @Benchmark
  public void copyWithNativeToArray() {
    try(final var scope = ResourceScope.newConfinedScope()) {
      final var src = srcAddress.asSegment(size, scope);

      MemorySegment.ofArray(dstArray).copyFrom(src);
    }
  }

  @Benchmark
  public void copyWithNativeShared() {
    dstSegmentShared.copyFrom(srcSegmentShared);
  }
}
