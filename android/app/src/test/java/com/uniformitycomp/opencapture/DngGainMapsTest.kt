package com.uniformitycomp.opencapture

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class DngGainMapsTest {
    internal fun opcode(width: Int=8, height: Int=8, base: (Int) -> Float = { 1f+it*.25f }): ByteArray {
        val b = ByteBuffer.allocate(4+4*(16+76+16)).order(ByteOrder.BIG_ENDIAN).putInt(4)
        repeat(4) { site ->
            b.putInt(9).putInt(0x01030000).putInt(1).putInt(92)
            for(v in listOf(site/2,site%2,height,width,0,1,2,2,2,2)) b.putInt(v)
            for(v in listOf(1.0,1.0,0.0,0.0)) b.putDouble(v)
            b.putInt(1)
            for(v in listOf(0f,.5f,.25f,.75f)) b.putFloat(base(site)+v)
        }
        return b.array()
    }
    private fun parse(bytes: ByteArray) = DngGainMaps.parse(bytes.map { it.toInt() and 255 },8,8)

    @Test fun distinctBayerChannelsUseDngPixelCenters() {
        val maps = parse(opcode())
        for(y in 0 until 8) for(x in 0 until 8) {
            val site = (y%2)*2+x%2
            val expected = 1+site*.25+.5*(x+.5)/8+.25*(y+.5)/8
            assertEquals(expected,maps[site].gain(x,y,8,8).toDouble(),1e-7)
        }
        val gains = (0..3).flatMap { point -> (0..3).map { site -> maps[site].gains[point] } }
        DngGainMaps.verify(maps,2,2,gains,listOf(0,1,1,2))
        for(cfa in listOf(listOf(0,1,1,2),listOf(1,0,2,1),listOf(1,2,0,1),listOf(2,1,1,0))) {
            val channels = cfa.mapIndexed { site,color -> when(color) { 0 -> 0; 2 -> 3; else -> if(site/2==0) 1 else 2 } }
            val reordered = (0..3).flatMap { point -> (0..3).map { channel -> maps[channels.indexOf(channel)].gains[point] } }
            DngGainMaps.verify(maps,2,2,reordered,cfa)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DngGainMaps.verify(maps,2,2,gains,listOf(2,1,1,0))
        }
    }

    @Test fun invalidProgramsFailClosed() {
        val bytes = opcode()
        val bad = mutableListOf(bytes.copyOf(bytes.size-1),bytes+byteArrayOf(0))
        // Count, opcode, version, flags, length, area, plane, pitch, points, map planes.
        for((offset,value) in listOf(0 to 5,4 to 5,8 to 0,12 to 4,16 to 75,20 to 2,28 to 9,
            36 to 1,44 to 0,52 to 0,56 to Int.MAX_VALUE,92 to 2,132 to 0)) {
            bad.add(bytes.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(offset,value) })
        }
        for(value in listOf(Float.NaN,Float.POSITIVE_INFINITY,.99f,16.01f))
            bad.add(bytes.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putFloat(96,value) })
        bad.add(bytes.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putDouble(60,.5) })
        for(item in bad) assertThrows(IllegalArgumentException::class.java) { parse(item) }
        assertTrue(DngGainMaps.parse(null,8,8).isEmpty())
    }

    @Test fun correctedAverageMatchesWindowsGoldenWithOddCropSignedNoiseAndNativeClips() {
        val directory = Files.createTempDirectory("gain-average-").toFile()
        try {
            val frames = (0..2).map { n ->
                val file = File(directory,"frame-$n.dng")
                file.writeBytes(DngSampleDiagnosticsTest().fixture(width=12,height=10,rows=3,
                    activeArea=listOf(1,3,9,11),opcode2=opcode(),
                    order=if(n==1)ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN) { x,y ->
                    when {
                        x==3 && y==1 -> 254+n
                        n==0 && x==4 && y==1 -> 4095
                        else -> 256+(x*17+y*23+n*3)%1700
                    }
                }.bytes)
                BayerAverage.inspect(file,12,10,"consistent_sampled_path") {}
            }
            BayerAverage.write(frames,directory) {}
            fun hash(name: String) = MessageDigest.getInstance("SHA-256").digest(File(directory,name).readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals("7f6c9aadfc2e4be197b660ad4dfbf32cab2453ac42b43e922c0c689ab7222bf1",hash("average.f32"))
            assertEquals("4cbbd8ca5215b8d161aec181a74b694f4e24b001d5b081dc0030ed797a8973e0",hash("average-clips.u8"))
            val data = ByteBuffer.wrap(File(directory,"average.f32").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            assertTrue(data.getFloat(0) < 0f)
            assertEquals(1,File(directory,"average-clips.u8").readBytes().sumOf { it.toInt() })
            val changed = frames.last().copy(layout=frames.last().layout.copy(gainMaps=parse(opcode { 2f+it*.25f })))
            assertThrows(IllegalArgumentException::class.java) { BayerAverage.write(listOf(frames[0],changed),directory) {} }
        } finally { directory.deleteRecursively() }
    }
}
