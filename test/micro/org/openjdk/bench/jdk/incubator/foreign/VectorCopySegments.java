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
  private int size = 1024;

  private MemoryAddress srcAddress;
  private MemoryAddress dstAddress;

  private ResourceScope sharedScope = ResourceScope.newSharedScope();

  private MemorySegment srcSegmentShared;
  private MemorySegment dstSegmentShared;

  private ByteBuffer srcBuffer;
  private ByteBuffer dstBuffer;

  private byte[] dstArray;

  private final static VectorShuffle<Byte> byteSwap;

  private static final int[] shuffleArr;
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
