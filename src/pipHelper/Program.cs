using System.Buffers;
using System.Buffers.Binary;
using System.Diagnostics;
using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Runtime.Intrinsics;
using System.Runtime.Intrinsics.X86;
using System.Text.Json;
using Windows.Graphics.Capture;
using Windows.Graphics.DirectX;
using Windows.Graphics.DirectX.Direct3D11;
using Windows.Graphics.Imaging;
using WinRT;

[ComImport, Guid("5B0D3235-4DBA-4D44-865E-8F1D0E4FD04D"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface MemoryAccess { void GetBuffer(out IntPtr buffer, out uint capacity); }

internal sealed record Packet(byte[] Bytes, int Length, int Width, int Height, long Sequence, long Captured, long Callback) : IDisposable {
    public void Dispose() => ArrayPool<byte>.Shared.Return(Bytes);
}

internal sealed class CaptureHost : Form {
    readonly IntPtr source,minecraft;
    readonly WindowIdentity identity;
    readonly Process parent;
    readonly int focusKey, returnKey, modifiers;
    readonly long parentStarted;
    readonly int cap;
    readonly FramePacer pacer;
    readonly NamedPipeServerStream pipe;
    readonly CancellationTokenSource cancel=new();
    readonly SemaphoreSlim available=new(0,1);
    readonly object publication=new(), logLock=new();
    readonly System.Windows.Forms.Timer timer=new(){Interval=250};
    readonly Stopwatch clock=Stopwatch.StartNew();
    readonly CaptureTrace? trace=CaptureTrace.Create();
    Packet? pending;
    IDirect3DDevice? device;
    Direct3D11CaptureFramePool? pool;
    GraphicsCaptureSession? session;
    GraphicsCaptureItem? captureItem;
    ProcessAudio? audio;
    readonly bool captureAudio;
    readonly string audioPipe;
    long arrivals,converted,sent,replaced,busyDropped,sequence,lastReport;
    long copyMicros,writeMicros;
    long acquired,throttled,lastArrivalQpc,lastAcquireQpc,lastSource100ns,lastCopyQpc,lastTransferQpc;
    int busy,width,height;
    volatile bool stopping;

    CaptureHost(string[] args) {
        if(args.Length!=11)throw new ArgumentException("Invalid capture arguments");
        captureAudio=args[10]=="1";audioPipe=args[6]+".audio";
        source=(IntPtr)long.Parse(args[1]);minecraft=(IntPtr)long.Parse(args[4]);
        identity=new(source.ToInt64(),int.Parse(args[2]),long.Parse(args[3]),"", "",false);
        parent=Process.GetProcessById(int.Parse(args[5]));parentStarted=parent.StartTime.ToUniversalTime().Ticks;
        Native.GetWindowThreadProcessId(minecraft,out var mcPid);
        if(mcPid!=parent.Id||!identity.Valid())throw new InvalidOperationException("invalid_target");
        cap=int.Parse(args[7]);focusKey=int.Parse(args[8]);returnKey=int.Parse(args[9]);modifiers=3;
        // Global shortcuts intentionally require Ctrl+Alt; F12 is reserved by Windows.
        if(cap<15||cap>480||focusKey<112||focusKey>135||returnKey<112||returnKey>135||focusKey==123||returnKey==123||focusKey==returnKey)throw new ArgumentException("Invalid capture options");
        pacer=new FramePacer(cap,Stopwatch.Frequency);
        pipe=new NamedPipeServerStream(args[6],PipeDirection.Out,1,PipeTransmissionMode.Byte,PipeOptions.Asynchronous|PipeOptions.CurrentUserOnly,0,65536);
        ShowInTaskbar=false;
        timer.Tick+=(_,_)=>{
            if(!MinecraftValid()){Close();return;}
            if(!identity.Valid()){Finish("source_closed");return;}
            if(clock.ElapsedMilliseconds-lastReport>=1000){lastReport=clock.ElapsedMilliseconds;Log(Native.IsIconic(source)?"minimized":"heartbeat",new{arrivals,acquired,converted,sent,replaced,busyDropped,throttled,copyMicros,writeMicros,lastArrivalQpc,lastAcquireQpc,lastSource100ns,lastCopyQpc,lastTransferQpc,qpcFrequency=Stopwatch.Frequency});}
        };
        _=Handle;
    }
    protected override void SetVisibleCore(bool value)=>base.SetVisibleCore(false);
    void Log(string kind,object data){lock(logLock){Console.WriteLine(JsonSerializer.Serialize(new{kind,data}));Console.Out.Flush();}}
    void Finish(string kind){if(stopping)return;Log(kind,new{});try{BeginInvoke(Close);}catch(InvalidOperationException){}}
    protected override void OnHandleCreated(EventArgs e){
        base.OnHandleCreated(e);
        try{
            if(!identity.Valid()||!Native.IsWindow(minecraft)||!GraphicsCaptureSession.IsSupported())throw new InvalidOperationException("Capture target or capability unavailable");
            if(!Native.RegisterHotKey(Handle,2,(uint)(0x4000|modifiers),(uint)returnKey)||!Native.RegisterHotKey(Handle,1,(uint)(0x4000|modifiers),(uint)focusKey))throw new InvalidOperationException("hotkey_conflict");
            device=Native.Device();captureItem=Native.Item(source);var item=captureItem;width=item.Size.Width;height=item.Size.Height;
            if(width>3840||height>2160){Finish("too_large");return;}
            item.Closed+=(_,_)=>Finish("source_closed");
            pool=Direct3D11CaptureFramePool.CreateFreeThreaded(device,DirectXPixelFormat.B8G8R8A8UIntNormalized,2,item.Size);
            pool.FrameArrived+=FrameArrived;session=pool.CreateCaptureSession(item);session.IsCursorCaptureEnabled=false;
            // Leave shipping behavior unchanged; only the explicit diagnostic override requests 4 ms.
            int? interval=Environment.GetEnvironmentVariable("DARKMUSIC_PIP_MIN_INTERVAL_MS")=="4"?4:null;
            var intervalResult=CaptureInterval.Inspect(session,interval);
            trace?.Add("interval",intervalResult.Supported?1:0,intervalResult.Error,intervalResult.BeforeTicks,intervalResult.EffectiveTicks);
            trace?.Add("clock",0,0,UnixMicros(),Stopwatch.Frequency);
            Log("configuration",new{interval=intervalResult,unixMicros=UnixMicros(),qpc=Stopwatch.GetTimestamp(),qpcFrequency=Stopwatch.Frequency});
            _=WriteFrames();_=Task.Run(ReadCommands);session.StartCapture();timer.Start();
            if(captureAudio)audio=new ProcessAudio((uint)identity.Pid,audioPipe,cancel.Token,Log);
            Log("ready",new{width,height,cap});
        }catch(Exception ex){Finish(ex.Message=="hotkey_conflict"?"hotkey_conflict":"capture_error");}
    }
    async Task WriteFrames(){
        try{
            await pipe.WaitForConnectionAsync(cancel.Token);
            var header=new byte[64];
            while(!cancel.IsCancellationRequested){
                await available.WaitAsync(cancel.Token);
                Packet? frame;lock(publication){frame=pending;pending=null;}
                if(frame==null)continue;
                using(frame){
                    header.AsSpan().Clear();
                    void I(int offset,int v)=>BinaryPrimitives.WriteInt32LittleEndian(header.AsSpan(offset,4),v);
                    void L(int offset,long v)=>BinaryPrimitives.WriteInt64LittleEndian(header.AsSpan(offset,8),v);
                    I(0,0x49504d44);I(4,1);I(8,frame.Width);I(12,frame.Height);I(16,frame.Width*4);I(20,frame.Length);
                    L(24,frame.Sequence);L(32,frame.Captured);L(40,UnixMicros());I(48,1);
                    long start=Stopwatch.GetTimestamp();
                    await pipe.WriteAsync(header,cancel.Token);
                    await pipe.WriteAsync(frame.Bytes.AsMemory(0,frame.Length),cancel.Token);
                    writeMicros=(long)Stopwatch.GetElapsedTime(start).TotalMicroseconds;Interlocked.Increment(ref sent);
                    lastTransferQpc=Stopwatch.GetTimestamp();trace?.Add("transfer",frame.Callback,frame.Sequence,value:writeMicros);
                }
            }
        }catch(Exception ex)when(ex is OperationCanceledException||stopping){}
        catch(Exception){Finish("pipe_error");}
    }
    static long UnixMicros()=>(DateTime.UtcNow.Ticks-DateTime.UnixEpoch.Ticks)/10;
    async void FrameArrived(Direct3D11CaptureFramePool sender,object _){
        long callback=Interlocked.Increment(ref arrivals);lastArrivalQpc=Stopwatch.GetTimestamp();trace?.Add("arrival",callback,0);
        if(stopping)return;
        if(Interlocked.CompareExchange(ref busy,1,0)!=0){Interlocked.Increment(ref busyDropped);return;}
        Direct3D11CaptureFrame? frame=null;
        try{
            frame=Latest(sender);if(frame==null)return;
            lastAcquireQpc=Stopwatch.GetTimestamp();lastSource100ns=frame.SystemRelativeTime.Ticks;trace?.Add("acquire",callback,0,lastSource100ns);
            if(!pacer.Accept(Stopwatch.GetTimestamp())){Interlocked.Increment(ref throttled);return;}
            var size=frame.ContentSize;
            if(size.Width<1||size.Height<1)return;
            if(size.Width>3840||size.Height>2160){Finish("too_large");return;}
            if(size.Width!=width||size.Height!=height){
                frame.Dispose();frame=null;width=size.Width;height=size.Height;
                sender.Recreate(device!,DirectXPixelFormat.B8G8R8A8UIntNormalized,2,size);
                Log("resize",new{width,height});return;
            }
            long start=Stopwatch.GetTimestamp();
            long captured=UnixMicros()-Math.Max(0,(long)(start*1_000_000.0/Stopwatch.Frequency-frame.SystemRelativeTime.TotalMicroseconds));
            using var bitmap=await SoftwareBitmap.CreateCopyFromSurfaceAsync(frame.Surface,BitmapAlphaMode.Ignore);
            if(bitmap.BitmapPixelFormat!=BitmapPixelFormat.Bgra8)throw new InvalidOperationException("Unexpected capture pixel format");
            int length=checked(width*height*4);byte[] bytes=ArrayPool<byte>.Shared.Rent(length);
            try{
                CopyRgba(bitmap,bytes,width,height);
                var packet=new Packet(bytes,length,width,height,Interlocked.Increment(ref sequence),captured,callback);bytes=null!;
                lastCopyQpc=Stopwatch.GetTimestamp();trace?.Add("copy",callback,packet.Sequence,lastSource100ns);
                lock(publication){
                    if(stopping){packet.Dispose();return;}
                    if(pending!=null){pending.Dispose();replaced++;}
                    pending=packet;if(available.CurrentCount==0)available.Release();
                }
                copyMicros=(long)Stopwatch.GetElapsedTime(start).TotalMicroseconds;Interlocked.Increment(ref converted);
            }finally{if(bytes!=null)ArrayPool<byte>.Shared.Return(bytes);}
        }catch(Exception){if(!stopping)Finish("capture_error");}
        finally{try{frame?.Dispose();}catch{}Volatile.Write(ref busy,0);}
    }
    static unsafe void CopyRgba(SoftwareBitmap bitmap,byte[] bytes,int width,int height){
        using var locked=bitmap.LockBuffer(BitmapBufferAccessMode.Read);using var reference=locked.CreateReference();
        reference.As<MemoryAccess>().GetBuffer(out var pointer,out uint capacity);var plane=locked.GetPlaneDescription(0);
        if(plane.Stride<width*4||plane.StartIndex<0||(long)plane.StartIndex+(long)(height-1)*plane.Stride+(long)width*4>capacity)throw new InvalidOperationException("Invalid capture surface bounds");
        var mask=Vector128.Create((byte)2,1,0,3,6,5,4,7,10,9,8,11,14,13,12,15);
        fixed(byte* output=bytes)for(int y=0;y<height;y++){
            byte* src=(byte*)pointer+plane.StartIndex+y*plane.Stride,dst=output+y*width*4;int x=0;
            if(Ssse3.IsSupported)for(;x+16<=width*4;x+=16)Sse2.Store(dst+x,Sse2.Or(Ssse3.Shuffle(Sse2.LoadVector128(src+x),mask),Vector128.Create(0xff000000u).AsByte()));
            for(;x<width*4;x+=4){dst[x]=src[x+2];dst[x+1]=src[x+1];dst[x+2]=src[x];dst[x+3]=255;}
        }
    }
    Direct3D11CaptureFrame? Latest(Direct3D11CaptureFramePool pool){
        var latest=pool.TryGetNextFrame();
        if(latest!=null)Interlocked.Increment(ref acquired);
        for(int i=0;i<2;i++){var next=pool.TryGetNextFrame();if(next==null)break;Interlocked.Increment(ref acquired);latest?.Dispose();latest=next;}
        return latest;
    }
    async Task ReadCommands(){
        while(!stopping){
            string? line=await Console.In.ReadLineAsync();
            if(line==null||line=="STOP"){Finish("stopped");return;}
            if(line=="FOCUS"||line=="RETURN")try{BeginInvoke(()=>FocusTarget(line=="FOCUS"));}catch(InvalidOperationException){return;}
        }
    }
    void FocusTarget(bool toSource){
        if(stopping||!identity.Valid()||!MinecraftValid())return;
        var foreground=Native.GetForegroundWindow();
        if(toSource&&foreground!=source&&foreground!=minecraft){Log("focus_denied",new{});return;}
        var target=toSource?source:minecraft;
        if(Native.IsIconic(target))Native.ShowWindow(target,9);
        bool accepted=Native.SetForegroundWindow(target);
        var verify=new System.Windows.Forms.Timer(){Interval=350};
        verify.Tick+=(_,_)=>{verify.Stop();verify.Dispose();Log(Native.GetForegroundWindow()==target?"focused":"focus_denied",new{accepted});};verify.Start();
    }
    bool MinecraftValid(){
        if(parent.HasExited||parent.StartTime.ToUniversalTime().Ticks!=parentStarted||!Native.IsWindow(minecraft))return false;
        Native.GetWindowThreadProcessId(minecraft,out var pid);return pid==parent.Id;
    }
    protected override void WndProc(ref Message m){
        if(m.Msg==0x312){int id=m.WParam.ToInt32();if(id==1||id==2)FocusTarget(id==1);}
        base.WndProc(ref m);
    }
    protected override void OnFormClosed(FormClosedEventArgs e){
        stopping=true;timer.Stop();cancel.Cancel();audio?.Dispose();
        // Disposal cancels a blocked pipe writer; an in-flight frame owns its own surface reference.
        session?.Dispose();pool?.Dispose();pipe.Dispose();
        lock(publication){pending?.Dispose();pending=null;}
        for(int id=1;id<=2;id++)Native.UnregisterHotKey(Handle,id);
        parent.Dispose();Log("closed",new{arrivals,converted,sent});base.OnFormClosed(e);
        trace?.Dispose();
    }
    [STAThread]static int Main(string[] args){
        Console.OutputEncoding=new System.Text.UTF8Encoding(false);
        if(args.Length==1&&args[0]=="--pacer-test"){bool pass=FramePacer.SelfTest();Console.WriteLine(pass?"pass":"fail");return pass?0:2;}
        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
        try{
            if(args.Length==1&&args[0]=="--list"){Console.WriteLine(JsonSerializer.Serialize(WindowIdentity.List()));return 0;}
            if(args.Length==1&&args[0]=="--supported"){Console.WriteLine(GraphicsCaptureSession.IsSupported()?"true":"false");return 0;}
            Application.Run(new CaptureHost(args));return 0;
        }catch(Exception){Console.WriteLine("{\"kind\":\"capture_error\",\"data\":{}}");return 1;}
    }
}
