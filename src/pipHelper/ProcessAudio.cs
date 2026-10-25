using System.Buffers.Binary;
using System.IO.Pipes;
using System.Runtime.InteropServices;

// Process-tree loopback only: no microphone, whole-system fallback, playback or recording.
public sealed class ProcessAudio : IDisposable {
    readonly CancellationTokenSource stop;
    readonly NamedPipeServerStream pipe;
    readonly Action<string,object> report;
    internal Task Completion { get; }
    internal ProcessAudio(uint pid,string name,CancellationToken parent,Action<string,object> report) {
        this.report=report;stop=CancellationTokenSource.CreateLinkedTokenSource(parent);
        pipe=new NamedPipeServerStream(name,PipeDirection.Out,1,PipeTransmissionMode.Byte,PipeOptions.Asynchronous|PipeOptions.CurrentUserOnly,0,32768);
        Completion=Task.Run(()=>Run(pid));
    }
    async Task Run(uint pid) {
        AudioClient? client=null;CaptureClient? capture=null;Activation? operation=null;
        string stage="connect";
        try {
            if(!OperatingSystem.IsWindowsVersionAtLeast(10,0,20348)) { report("audio_unsupported",new{});return; }
            await pipe.WaitForConnectionAsync(stop.Token).WaitAsync(TimeSpan.FromSeconds(15),stop.Token);
            var callback=new Callback();
            stage="activate";
            var iid=typeof(AudioClient).GUID;
            IntPtr args=Marshal.AllocHGlobal(12);
            try {
                Marshal.WriteInt32(args,0,1);Marshal.WriteInt32(args,4,checked((int)pid));Marshal.WriteInt32(args,8,0);
                var variant=new BlobVariant{Type=65,Size=12,Data=args};
                Check(ActivateAudioInterfaceAsync("VAD\\Process_Loopback",ref iid,ref variant,callback,out operation));
                client=(AudioClient)await callback.Result.Task.WaitAsync(TimeSpan.FromSeconds(10),stop.Token);
            } finally { Marshal.FreeHGlobal(args); }
            // Fixed PCM format keeps the local transport bounded and avoids FFT work in the helper.
            var format=new WaveFormat{Tag=1,Channels=2,Rate=44100,BytesPerSecond=176400,Align=4,Bits=16};
            stage="initialize";
            Check(client.Initialize(0,0x80060000,0,0,ref format,IntPtr.Zero));
            using var ready=new EventWaitHandle(false,EventResetMode.AutoReset);
            stage="event";
            Check(client.SetEventHandle(ready.SafeWaitHandle.DangerousGetHandle()));
            var captureId=typeof(CaptureClient).GUID;Check(client.GetService(ref captureId,out var service));
            stage="service";
            try { capture=(CaptureClient)Marshal.GetObjectForIUnknown(service); } finally { Marshal.Release(service); }
            stage="start";Check(client.Start());report("audio_ready",new{});stage="capture";
            // Stay on one MTA worker between GetBuffer and ReleaseBuffer. Never wait on video delivery.
            var bytes=new byte[32768];var header=new byte[32];long sequence=0;
            var waits=new WaitHandle[]{stop.Token.WaitHandle,ready};
            while(!stop.IsCancellationRequested) {
                int signaled=WaitHandle.WaitAny(waits,100);
                if(signaled==0)break;
                Check(capture.GetNextPacketSize(out uint available));
                if(available==0) {
                    // Silence must decay the visualizer even when a paused application emits no packets.
                    if(signaled==WaitHandle.WaitTimeout){Array.Clear(bytes,0,17640);Write(4410,0,++sequence,header,bytes);}
                    continue;
                }
                for(int drained=0;available>0&&drained<64&&!stop.IsCancellationRequested;drained++) {
                    Check(capture.GetBuffer(out var data,out uint frames,out uint flags,out _,out _));
                    if(frames==0)break;
                    try {
                        if(frames>8192)throw new IOException("Oversized loopback packet");
                        int length=checked((int)frames*4);
                        if((flags&2)!=0)Array.Clear(bytes,0,length);else Marshal.Copy(data,bytes,0,length);
                    } finally { Check(capture.ReleaseBuffer(frames)); }
                    Write((int)frames,(int)(flags&1),++sequence,header,bytes);
                    Check(capture.GetNextPacketSize(out available));
                }
            }
        } catch(Exception ex) when(ex is OperationCanceledException||stop.IsCancellationRequested) {}
        catch(Exception ex) { report("audio_error",new{code=ex.HResult,stage}); }
        finally {
            if(client!=null)try{client.Stop();}catch{}
            if(capture!=null)Marshal.ReleaseComObject(capture);
            if(client!=null)Marshal.ReleaseComObject(client);
            if(operation!=null)Marshal.ReleaseComObject(operation);
            pipe.Dispose();
        }
    }
    void Write(int frames,int flags,long sequence,byte[] header,byte[] bytes) {
        BinaryPrimitives.WriteInt32LittleEndian(header.AsSpan(0,4),0x414D4444);
        BinaryPrimitives.WriteInt32LittleEndian(header.AsSpan(4,4),1);
        BinaryPrimitives.WriteInt32LittleEndian(header.AsSpan(8,4),44100);
        BinaryPrimitives.WriteInt32LittleEndian(header.AsSpan(12,4),2);
        BinaryPrimitives.WriteInt32LittleEndian(header.AsSpan(16,4),frames);
        BinaryPrimitives.WriteInt32LittleEndian(header.AsSpan(20,4),flags);
        BinaryPrimitives.WriteInt64LittleEndian(header.AsSpan(24,8),sequence);
        // One small in-flight write; a stalled consumer cannot allocate a backlog.
        pipe.WriteAsync(header,stop.Token).AsTask().WaitAsync(TimeSpan.FromSeconds(2),stop.Token).GetAwaiter().GetResult();
        pipe.WriteAsync(bytes.AsMemory(0,frames*4),stop.Token).AsTask().WaitAsync(TimeSpan.FromSeconds(2),stop.Token).GetAwaiter().GetResult();
    }
    internal static void Check(int result)=>Marshal.ThrowExceptionForHR(result);
    public void Dispose(){stop.Cancel();pipe.Dispose();}
    [DllImport("Mmdevapi.dll",CharSet=CharSet.Unicode,ExactSpelling=true)]
    static extern int ActivateAudioInterfaceAsync(string path,ref Guid iid,ref BlobVariant args,CompletionHandler callback,out Activation operation);
    [StructLayout(LayoutKind.Explicit,Size=24)] struct BlobVariant {
        [FieldOffset(0)]public ushort Type;[FieldOffset(8)]public int Size;[FieldOffset(16)]public IntPtr Data;
    }
    [StructLayout(LayoutKind.Sequential,Pack=2)] struct WaveFormat {
        public ushort Tag,Channels;public uint Rate,BytesPerSecond;public ushort Align,Bits,Extra;
    }
    [ComVisible(true),Guid("41D949AB-9862-444A-80F6-C261334DA5EB"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface CompletionHandler { [PreserveSig]int ActivateCompleted(Activation operation); }
    [ComImport,Guid("72A22D78-CDE4-431D-B8CC-843A71199B6D"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface Activation { [PreserveSig]int GetActivateResult(out int result,[MarshalAs(UnmanagedType.IUnknown)]out object client); }
    [ComVisible(true),Guid("94EA2B94-E9CC-49E0-C0FF-EE64CA8F5B90"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface Agile {}
    [ComVisible(true),ClassInterface(ClassInterfaceType.None)]
    public sealed class Callback : CompletionHandler,Agile {
        internal readonly TaskCompletionSource<object> Result=new(TaskCreationOptions.RunContinuationsAsynchronously);
        public int ActivateCompleted(Activation operation) {
            try{Check(operation.GetActivateResult(out int hr,out var client));Check(hr);Result.TrySetResult(client);}
            catch(Exception ex){Result.TrySetException(ex);}return 0;
        }
    }
    [ComImport,Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface AudioClient {
        [PreserveSig]int Initialize(int share,uint flags,long buffer,long periodicity,ref WaveFormat format,IntPtr session);
        [PreserveSig]int GetBufferSize(out uint frames);
        [PreserveSig]int GetStreamLatency(out long latency);
        [PreserveSig]int GetCurrentPadding(out uint frames);
        [PreserveSig]int IsFormatSupported(int share,IntPtr format,out IntPtr closest);
        [PreserveSig]int GetMixFormat(out IntPtr format);
        [PreserveSig]int GetDevicePeriod(out long normal,out long minimum);
        [PreserveSig]int Start();[PreserveSig]int Stop();[PreserveSig]int Reset();
        [PreserveSig]int SetEventHandle(IntPtr handle);
        [PreserveSig]int GetService(ref Guid iid,out IntPtr service);
    }
    [ComImport,Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface CaptureClient {
        [PreserveSig]int GetBuffer(out IntPtr data,out uint frames,out uint flags,out ulong position,out ulong qpc);
        [PreserveSig]int ReleaseBuffer(uint frames);[PreserveSig]int GetNextPacketSize(out uint frames);
    }
}
