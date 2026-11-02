using System.Text.Json;

internal static class ResamplerVectors {
    internal static unsafe void Run(bool useGpu=false){
        GpuResampler? gpu=null;
        if(useGpu){var device=Native.Device(Environment.GetEnvironmentVariable("DARKMUSIC_PIP_GPU_WARP")=="1");gpu=new GpuResampler(device);GC.KeepAlive(device);}
        var resampler=new Resampler();
        string? line;
        while((line=Console.ReadLine())!=null){
            using var json=JsonDocument.Parse(line);var root=json.RootElement;
            int width=root.GetProperty("width").GetInt32(),height=root.GetProperty("height").GetInt32();
            byte[] data=root.GetProperty("data").GetBytesFromBase64();
            var t=CaptureTarget.Parse(root.GetProperty("target").GetString()??"");
            if(!CaptureBounds.Valid(width,height)||data.Length!=checked(width*height*4)||t==null||t!=t.Clamp(width,height))throw new ArgumentException("Invalid test vector");
            var output=new byte[t.OutWidth*t.OutHeight*4];
            if(gpu!=null){
                gpu.Upload(data,width,height,t);var timeout=System.Diagnostics.Stopwatch.StartNew();
                GpuResampler.Readback? result;
                while(!gpu.TryRead(out result)){
                    if(timeout.Elapsed.TotalSeconds>10)throw new TimeoutException("GPU readback stalled");
                    Thread.Sleep(1);
                }
                Array.Copy(result!.Bytes,output,output.Length);
                System.Buffers.ArrayPool<byte>.Shared.Return(result.Bytes);
            }else fixed(byte* p=data)resampler.Copy(p,width*4,output,t);
            Console.WriteLine(Convert.ToBase64String(output));
        }
        gpu?.VerifyBoundedBurst();
        gpu?.Dispose();
    }
}
