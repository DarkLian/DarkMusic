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

internal sealed record Packet(byte[] Bytes, int Length, int Width, int Height, long Sequence, long Captured, long Callback, int SourceWidth, int SourceHeight, CaptureTarget Target) : IDisposable {
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
    volatile CaptureTarget? target;
    SoftwareBitmap? cachedBitmap;
    long cachedCaptured,cachedCallback;
    CaptureTarget? lastConverted;
    readonly Resampler resampler=new();
    GpuResampler? gpu;
    readonly AutoResetEvent gpuAvailable=new(false);
    IDirect3DDevice? device;
    Direct3D11CaptureFramePool? pool;
    GraphicsCaptureSession? session;
    GraphicsCaptureItem? captureItem;
    ProcessAudio? audio;
    readonly bool captureAudio;
    readonly string audioPipe;
    long arrivals,converted,sent,replaced,busyDropped,sequence,lastReport,lastGpuReplaced;
    long copyMicros,writeMicros;
    long acquired,throttled,lastArrivalQpc,lastAcquireQpc,lastSource100ns,lastCopyQpc,lastTransferQpc;
    int busy,width,height,sourceRefreshRate;
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
            if(clock.ElapsedMilliseconds-lastReport>=1000){RefreshInterval();lastReport=clock.ElapsedMilliseconds;Log(Native.IsIconic(source)?"minimized":"heartbeat",new{arrivals,acquired,converted,sent,replaced,busyDropped,throttled,copyMicros,writeMicros,lastArrivalQpc,lastAcquireQpc,lastSource100ns,lastCopyQpc,lastTransferQpc,gpuReplaced=gpu?.Replaced??0,qpcFrequency=Stopwatch.Frequency});}
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
            if(!CaptureBounds.Valid(width,height)){Finish("too_large");return;}
            item.Closed+=(_,_)=>Finish("source_closed");
            if(Environment.GetEnvironmentVariable("DARKMUSIC_PIP_GPU")!="0")try{gpu=new GpuResampler(device);}catch(Exception error){Log("gpu_fallback",new{error=error.HResult});}
            pool=Direct3D11CaptureFramePool.CreateFreeThreaded(device,DirectXPixelFormat.B8G8R8A8UIntNormalized,3,item.Size);
            pool.FrameArrived+=FrameArrived;session=pool.CreateCaptureSession(item);session.IsCursorCaptureEnabled=false;
            // The default 16 ms interval caps delivery near 48 FPS on a 144 Hz display, so follow the chosen limit; 4 ms stays a diagnostic override.
            sourceRefreshRate=CaptureInterval.SourceRefreshRate(source);
            long? interval=Environment.GetEnvironmentVariable("DARKMUSIC_PIP_MIN_INTERVAL_MS")=="4"?40_000:CaptureInterval.TicksFor(cap,sourceRefreshRate);
            var intervalResult=CaptureInterval.Inspect(session,interval);
            trace?.Add("interval",intervalResult.Supported?1:0,intervalResult.Error,intervalResult.BeforeTicks,intervalResult.EffectiveTicks);
            trace?.Add("clock",0,0,UnixMicros(),Stopwatch.Frequency);
            Log("configuration",new{interval=intervalResult,framePoolBuffers=3,gpuStage=gpu!=null,sourceRefreshRate,unixMicros=UnixMicros(),qpc=Stopwatch.GetTimestamp(),qpcFrequency=Stopwatch.Frequency});
            _=WriteFrames();if(gpu!=null)new Thread(ReadGpu){IsBackground=true,Name="darkmusic-pip-gpu"}.Start();_=Task.Run(ReadCommands);session.StartCapture();timer.Start();
            if(captureAudio)_=RunAudio();
            Log("ready",new{width,height,cap});
        }catch(Exception ex){Finish(ex.Message=="hotkey_conflict"?"hotkey_conflict":"capture_error");}
    }
    void RefreshInterval(){
        if(session==null||stopping||Environment.GetEnvironmentVariable("DARKMUSIC_PIP_MIN_INTERVAL_MS")=="4")return;
        int rate=CaptureInterval.SourceRefreshRate(source);if(rate==0||rate==sourceRefreshRate)return;
        sourceRefreshRate=rate;
        var result=CaptureInterval.Inspect(session,CaptureInterval.TicksFor(cap,rate));
        trace?.Add("interval",result.Supported?1:0,result.Error,result.BeforeTicks,result.EffectiveTicks);
        Log("configuration",new{interval=result,sourceRefreshRate=rate});
    }
    async Task RunAudio(){
        await AudioRecovery.Run(async token=>{
            bool unsupported=false;
            using var attempt=new ProcessAudio((uint)identity.Pid,audioPipe,token,(kind,data)=>{
                if(kind=="audio_unsupported"){unsupported=true;Log(kind,data);}
                else Log(kind=="audio_error"?"audio_retry":kind,data);
            });
            audio=attempt;
            await attempt.Completion;
            return unsupported;
        },cancel.Token,Log);
    }
    void RecreatePool(Direct3D11CaptureFramePool sender,Windows.Graphics.SizeInt32 size){
        width=size.Width;height=size.Height;
        sender.Recreate(device!,DirectXPixelFormat.B8G8R8A8UIntNormalized,3,size);
        lock(publication){pending?.Dispose();pending=null;}
        Log("resize",new{width,height});
    }
    async Task WriteFrames(){
        try{
            await pipe.WaitForConnectionAsync(cancel.Token);
            var header=new byte[96];
            while(!cancel.IsCancellationRequested){
                await available.WaitAsync(cancel.Token);
                Packet? frame;lock(publication){frame=pending;pending=null;}
                if(frame==null)continue;
                using(frame){
                    header.AsSpan().Clear();
                    void I(int offset,int v)=>BinaryPrimitives.WriteInt32LittleEndian(header.AsSpan(offset,4),v);
                    void L(int offset,long v)=>BinaryPrimitives.WriteInt64LittleEndian(header.AsSpan(offset,8),v);
                    I(0,0x49504d44);I(4,2);I(8,frame.Width);I(12,frame.Height);I(16,frame.Width*4);I(20,frame.Length);
                    L(24,frame.Sequence);L(32,frame.Captured);L(40,UnixMicros());I(48,1);
                    I(64,frame.SourceWidth);I(68,frame.SourceHeight);I(72,frame.Target.X);I(76,frame.Target.Y);I(80,frame.Target.Width);I(84,frame.Target.Height);
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
        if(Interlocked.CompareExchange(ref busy,1,0)!=0){Interlocked.Increment(ref busyDropped);trace?.Add("busy",callback,0);return;}
        Direct3D11CaptureFrame? frame=null;
        try{
            frame=Latest(sender);if(frame==null)return;
            lastAcquireQpc=Stopwatch.GetTimestamp();lastSource100ns=frame.SystemRelativeTime.Ticks;trace?.Add("acquire",callback,0,lastSource100ns);
            if(!pacer.Accept(Stopwatch.GetTimestamp())){Interlocked.Increment(ref throttled);return;}
            var size=frame.ContentSize;
            if(size.Width<1||size.Height<1)return;
            if(!CaptureBounds.Valid(size.Width,size.Height)){Finish("too_large");return;}
            if(size.Width!=width||size.Height!=height){
                frame.Dispose();frame=null;RecreatePool(sender,size);return;
            }
            long start=Stopwatch.GetTimestamp();
            long captured=UnixMicros()-Math.Max(0,(long)(start*1_000_000.0/Stopwatch.Frequency-frame.SystemRelativeTime.TotalMicroseconds));
            if(gpu!=null){
                var request=(target??new CaptureTarget(0,0,width,height,width,height)).Clamp(width,height);
                if(!gpu.Submit(frame.Surface,width,height,request,captured,callback)){
                    frame.Dispose();frame=null;RecreatePool(sender,size);
                }
                gpuAvailable.Set();
                long gpuDropped=gpu.Replaced;
                if(gpuDropped>lastGpuReplaced){trace?.Add("staging_drop",callback,0,value:gpuDropped-lastGpuReplaced);lastGpuReplaced=gpuDropped;}
                return;
            }
            var bitmap=await SoftwareBitmap.CreateCopyFromSurfaceAsync(frame.Surface,BitmapAlphaMode.Ignore);
            trace?.Add("readback",callback,0,value:(long)Stopwatch.GetElapsedTime(start).TotalMicroseconds);
            if(!CaptureBounds.Matches(bitmap.PixelWidth,bitmap.PixelHeight,width,height)){
                bitmap.Dispose();
                // A queued surface may still belong to the previous pool after a resize.
                frame.Dispose();frame=null;RecreatePool(sender,size);return;
            }
            if(bitmap.BitmapPixelFormat!=BitmapPixelFormat.Bgra8){bitmap.Dispose();throw new InvalidOperationException("Unexpected capture pixel format");}
            cachedBitmap?.Dispose();cachedBitmap=bitmap;cachedCaptured=captured;cachedCallback=callback;
            ConvertBitmap(bitmap,captured,callback,start);
        }catch(Exception){if(!stopping)Finish("capture_error");}
        finally{try{frame?.Dispose();}catch{}Volatile.Write(ref busy,0);}
    }
    void ReadGpu(){
        try{using var wait=new CaptureWait();while(!stopping){
            if(gpu!.TryRead(out var result)){
                var r=result!;
                var packet=new Packet(r.Bytes,r.Length,r.Target.OutWidth,r.Target.OutHeight,Interlocked.Increment(ref sequence),r.Captured,r.Callback,r.SourceWidth,r.SourceHeight,r.Target);
                trace?.Add("readback",r.Callback,packet.Sequence,value:r.ReadbackMicros);
                trace?.Add("convert",r.Callback,packet.Sequence,value:r.CopyMicros);
                lastCopyQpc=Stopwatch.GetTimestamp();trace?.Add("copy",r.Callback,packet.Sequence);
                Publish(packet);Interlocked.Increment(ref converted);
            }else if(gpu.Pending)wait.Once();else gpuAvailable.WaitOne(100);
        }}catch{if(!stopping)Finish("capture_error");}
    }
    void Publish(Packet packet){
        lock(publication){
            if(stopping){packet.Dispose();return;}
            if(pending!=null){pending.Dispose();replaced++;}
            pending=packet;lastConverted=packet.Target;if(available.CurrentCount==0)available.Release();
        }
    }
    void ConvertBitmap(SoftwareBitmap bitmap,long captured,long callback,long start,bool targetOnly=false){
            int width=bitmap.PixelWidth,height=bitmap.PixelHeight;
            var requested=(target??new CaptureTarget(0,0,width,height,width,height)).Clamp(width,height);
            if(targetOnly&&requested==lastConverted)return;
            int length=checked(requested.OutWidth*requested.OutHeight*4);byte[] bytes=ArrayPool<byte>.Shared.Rent(length);
            try{
                long convertStart=Stopwatch.GetTimestamp();
                CopyRgba(bitmap,bytes,width,height,requested);
                trace?.Add("convert",callback,0,value:(long)Stopwatch.GetElapsedTime(convertStart).TotalMicroseconds);
                var packet=new Packet(bytes,length,requested.OutWidth,requested.OutHeight,Interlocked.Increment(ref sequence),captured,callback,width,height,requested);bytes=null!;
                lastCopyQpc=Stopwatch.GetTimestamp();trace?.Add("copy",callback,packet.Sequence,lastSource100ns);
                Publish(packet);
                copyMicros=(long)Stopwatch.GetElapsedTime(start).TotalMicroseconds;Interlocked.Increment(ref converted);
            }finally{if(bytes!=null)ArrayPool<byte>.Shared.Return(bytes);}
    }
    async Task Reprocess(){
        while(!stopping){
            if(Interlocked.CompareExchange(ref busy,1,0)==0){
                try{if(!stopping&&cachedBitmap!=null)ConvertBitmap(cachedBitmap,cachedCaptured,cachedCallback,Stopwatch.GetTimestamp(),true);}
                catch{if(!stopping)Finish("capture_error");}
                finally{Volatile.Write(ref busy,0);}
                return;
            }
            await Task.Delay(1);
        }
    }
    unsafe void CopyRgba(SoftwareBitmap bitmap,byte[] bytes,int width,int height,CaptureTarget target){
        using var locked=bitmap.LockBuffer(BitmapBufferAccessMode.Read);using var reference=locked.CreateReference();
        reference.As<MemoryAccess>().GetBuffer(out var pointer,out uint capacity);var plane=locked.GetPlaneDescription(0);
        if(plane.Stride<width*4||plane.StartIndex<0||(long)plane.StartIndex+(long)(height-1)*plane.Stride+(long)width*4>capacity)throw new InvalidOperationException("Invalid capture surface bounds");
        if(target.X!=0||target.Y!=0||target.Width!=width||target.Height!=height||target.OutWidth!=width||target.OutHeight!=height){
            resampler.Copy((byte*)pointer+plane.StartIndex,plane.Stride,bytes.AsSpan(0,target.OutWidth*target.OutHeight*4),target);return;
        }
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
            if(line.StartsWith("TARGET ",StringComparison.Ordinal)){var requested=CaptureTarget.Parse(line);if(requested!=null){target=requested;if(gpu!=null){try{gpu.Retarget(requested);gpuAvailable.Set();}catch{Finish("capture_error");return;}}else _=Task.Run(Reprocess);}continue;}
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
        session?.Dispose();pool?.Dispose();gpu?.Dispose();pipe.Dispose();
        lock(publication){pending?.Dispose();pending=null;}
        for(int id=1;id<=2;id++)Native.UnregisterHotKey(Handle,id);
        parent.Dispose();Log("closed",new{arrivals,converted,sent});base.OnFormClosed(e);
        _=Task.Run(async()=>{
            while(Interlocked.CompareExchange(ref busy,1,0)!=0)await Task.Delay(1);
            try{cachedBitmap?.Dispose();cachedBitmap=null;}finally{Volatile.Write(ref busy,0);}
        });
        trace?.Dispose();
    }
    internal static void RunMessageLoop(Form host,Action failure){
        System.Threading.ThreadExceptionEventHandler failed=(_,_)=>failure();
        Application.ThreadException+=failed;
        try{Application.Run(host);}finally{Application.ThreadException-=failed;}
    }
    [STAThread]static int Main(string[] args){
        Console.OutputEncoding=new System.Text.UTF8Encoding(false);
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        if(args.Length==1&&args[0]=="--reliability-test"){bool pass=CaptureReliabilityTests.Run();Console.WriteLine(pass?"pass":"fail");return pass?0:2;}
        if(args.Length==1&&args[0]=="--pacer-test"){bool pass=FramePacer.SelfTest();Console.WriteLine(pass?"pass":"fail");return pass?0:2;}
        if(args.Length==1&&args[0]=="--resample-test"){bool pass=Resampler.SelfTest();Console.WriteLine(pass?"pass":"fail");return pass?0:2;}
        if(args.Length==1&&args[0]=="--resample-vectors"){ResamplerVectors.Run();return 0;}
        if(args.Length==1&&args[0]=="--gpu-resample-vectors"){ResamplerVectors.Run(true);return 0;}
        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
        try{
            if(args.Length==1&&args[0]=="--requirements"){
                if(Environment.OSVersion.Version.Build<19041||!GraphicsCaptureSession.IsSupported()){Console.WriteLine("unsupported");return 2;}
                using var testDevice=Native.Device();
                Console.WriteLine("supported");return 0;
            }
            if(args.Length==1&&args[0]=="--list"){Console.WriteLine(JsonSerializer.Serialize(WindowIdentity.List()));return 0;}
            if(args.Length==1&&args[0]=="--supported"){Console.WriteLine(GraphicsCaptureSession.IsSupported()?"true":"false");return 0;}
            using var host=new CaptureHost(args);
            RunMessageLoop(host,()=>host.Finish("capture_error"));
            return 0;
        }catch(Exception){Console.WriteLine("{\"kind\":\"capture_error\",\"data\":{}}");return 1;}
    }
}
