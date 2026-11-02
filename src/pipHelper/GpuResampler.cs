using System.Buffers;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using Windows.Graphics.DirectX.Direct3D11;

// All immediate-context access is serialized. Three small readback slots never wait for the GPU.
// The shader uses the CPU reference's exact integer pixel coverage, including its final half-up rounding.
internal sealed unsafe class GpuResampler : IDisposable {
    [StructLayout(LayoutKind.Sequential)]struct TextureDesc {internal uint Width,Height,Mips,Array,Format,SampleCount,SampleQuality,Usage,Bind,Cpu,Misc;}
    [StructLayout(LayoutKind.Sequential)]struct BufferDesc {internal uint Bytes,Usage,Bind,Cpu,Misc,Stride;}
    [StructLayout(LayoutKind.Sequential)]struct Mapped {internal IntPtr Data;internal uint RowPitch,DepthPitch;}
    sealed class Slot {
        internal IntPtr Texture;internal int Width,Height,SourceWidth,SourceHeight;
        internal long Order,Captured,Callback,Submitted;
        internal CaptureTarget? Target;
    }
    internal sealed record Readback(byte[] Bytes,int Length,int SourceWidth,int SourceHeight,CaptureTarget Target,
        long Captured,long Callback,long ReadbackMicros,long CopyMicros);
    readonly object sync=new();readonly Slot[] slots={new(),new(),new()};
    IntPtr device,context,shader,constants,sourceTexture,sourceView,outputTexture,outputView;
    int sourceWidth,sourceHeight,outputWidth,outputHeight;
    long order,captured,callback;CaptureTarget? lastTarget,deferredTarget;bool closed;
    internal long Replaced {get;private set;}
    internal bool Pending {get{lock(sync)return !closed&&slots.Any(s=>s.Order!=0);}}
    static IntPtr Method(IntPtr value,int slot)=>Marshal.ReadIntPtr(Marshal.ReadIntPtr(value),slot*IntPtr.Size);
    static void Release(ref IntPtr value){if(value!=IntPtr.Zero){Marshal.Release(value);value=IntPtr.Zero;}}
    static IntPtr Dxgi(object value,Guid iid){
        IntPtr inspect=WinRT.MarshalInspectable<object>.FromManaged(value),access=IntPtr.Zero,result=IntPtr.Zero;
        try{
            var accessId=new Guid("A9B3D012-3DF2-4EE3-B8D1-8695F457D3C1");
            Marshal.ThrowExceptionForHR(Marshal.QueryInterface(inspect,ref accessId,out access));
            Marshal.ThrowExceptionForHR(((delegate* unmanaged[Stdcall]<IntPtr,Guid*,IntPtr*,int>)Method(access,3))(access,&iid,&result));
            return result;
        }finally{Release(ref access);Release(ref inspect);GC.KeepAlive(value);}
    }
    internal GpuResampler(IDirect3DDevice captureDevice){
        try{
            device=Dxgi(captureDevice,new("DB6F6DDB-AC77-4E88-8253-819DF9BBF140"));
            IntPtr c=IntPtr.Zero;((delegate* unmanaged[Stdcall]<IntPtr,IntPtr*,void>)Method(device,40))(device,&c);context=c;
            var multithreadId=new Guid("9B7E4E00-342C-4106-A19F-4F2704F689F0");IntPtr multithread=IntPtr.Zero;
            if(Marshal.QueryInterface(context,ref multithreadId,out multithread)>=0){
                try{((delegate* unmanaged[Stdcall]<IntPtr,int,int>)Method(multithread,5))(multithread,1);}finally{Release(ref multithread);}
            }
            Compile();var desc=new BufferDesc{Bytes=32,Bind=4};IntPtr buffer=IntPtr.Zero;
            Marshal.ThrowExceptionForHR(((delegate* unmanaged[Stdcall]<IntPtr,BufferDesc*,IntPtr,IntPtr*,int>)Method(device,3))(device,&desc,IntPtr.Zero,&buffer));constants=buffer;
        }catch{Dispose();throw;}
    }
    void Compile(){
        var data=Encoding.UTF8.GetBytes(Shader);IntPtr code=IntPtr.Zero,errors=IntPtr.Zero;
        var library=NativeLibrary.Load(Path.Combine(Environment.SystemDirectory,"d3dcompiler_47.dll"));
        try{
            var compile=(delegate* unmanaged[Stdcall]<byte*,nuint,byte*,IntPtr,IntPtr,byte*,byte*,uint,uint,IntPtr*,IntPtr*,int>)NativeLibrary.GetExport(library,"D3DCompile");
            int result;
            fixed(byte* input=data)fixed(byte* name="DarkMusicArea\0"u8)fixed(byte* entry="main\0"u8)fixed(byte* profile="cs_5_0\0"u8)
                result=compile(input,(nuint)data.Length,name,IntPtr.Zero,IntPtr.Zero,entry,profile,0x8800,0,&code,&errors);
            if(result<0){
                var message=errors==IntPtr.Zero?"Shader compilation failed":Marshal.PtrToStringAnsi(((delegate* unmanaged[Stdcall]<IntPtr,IntPtr>)Method(errors,3))(errors));
                throw new InvalidOperationException(message);
            }
            var pointer=((delegate* unmanaged[Stdcall]<IntPtr,IntPtr>)Method(code,3))(code);
            var length=((delegate* unmanaged[Stdcall]<IntPtr,nuint>)Method(code,4))(code);IntPtr value=IntPtr.Zero;
            Marshal.ThrowExceptionForHR(((delegate* unmanaged[Stdcall]<IntPtr,IntPtr,nuint,IntPtr,IntPtr*,int>)Method(device,18))(device,pointer,length,IntPtr.Zero,&value));shader=value;
        }finally{Release(ref code);Release(ref errors);NativeLibrary.Free(library);}
    }
    IntPtr Texture(int width,int height,uint format,uint usage,uint bind,uint cpu){
        var desc=new TextureDesc{Width=(uint)width,Height=(uint)height,Mips=1,Array=1,Format=format,SampleCount=1,Usage=usage,Bind=bind,Cpu=cpu};
        IntPtr value=IntPtr.Zero;
        Marshal.ThrowExceptionForHR(((delegate* unmanaged[Stdcall]<IntPtr,TextureDesc*,IntPtr,IntPtr*,int>)Method(device,5))(device,&desc,IntPtr.Zero,&value));return value;
    }
    IntPtr View(IntPtr texture,int slot){
        IntPtr value=IntPtr.Zero;
        Marshal.ThrowExceptionForHR(((delegate* unmanaged[Stdcall]<IntPtr,IntPtr,IntPtr,IntPtr*,int>)Method(device,slot))(device,texture,IntPtr.Zero,&value));return value;
    }
    void Source(int width,int height){
        if(sourceTexture!=IntPtr.Zero&&width==sourceWidth&&height==sourceHeight)return;
        Release(ref sourceView);Release(ref sourceTexture);sourceWidth=width;sourceHeight=height;
        sourceTexture=Texture(width,height,87,0,8,0);sourceView=View(sourceTexture,7);lastTarget=null;
    }
    internal bool Submit(IDirect3DSurface surface,int width,int height,CaptureTarget target,long timestamp,long arrival){
        var texture=Dxgi(surface,new("6F15AAF2-D208-4E89-9AB4-489535D34F9C"));
        try{lock(sync){
            if(closed)return false;
            TextureDesc desc;((delegate* unmanaged[Stdcall]<IntPtr,TextureDesc*,void>)Method(texture,10))(texture,&desc);
            if(desc.Width!=width||desc.Height!=height||desc.Format!=87)return false;
            Source(width,height);
            ((delegate* unmanaged[Stdcall]<IntPtr,IntPtr,IntPtr,void>)Method(context,47))(context,sourceTexture,texture);
            captured=timestamp;callback=arrival;Dispatch(target.Clamp(width,height));return true;
        }}finally{Release(ref texture);}
    }
    internal void Retarget(CaptureTarget target){lock(sync){if(!closed&&sourceTexture!=IntPtr.Zero){target=target.Clamp(sourceWidth,sourceHeight);if(target!=lastTarget||deferredTarget!=null)Dispatch(target);}}}
    void Dispatch(CaptureTarget target){
        var slot=slots.FirstOrDefault(s=>s.Order==0);
        if(slot==null){Replaced++;deferredTarget=target;return;}
        deferredTarget=null;
        long submitted=Stopwatch.GetTimestamp();int width=target.OutWidth,height=target.OutHeight;
        if(outputTexture==IntPtr.Zero||outputWidth!=width||outputHeight!=height){
            Release(ref outputView);Release(ref outputTexture);outputWidth=width;outputHeight=height;
            foreach(var unused in slots.Where(s=>s.Order==0))Release(ref unused.Texture);
            outputTexture=Texture(width,height,28,0,128,0);outputView=View(outputTexture,8);
        }
        uint* parameters=stackalloc uint[8]{(uint)target.X,(uint)target.Y,(uint)target.Width,(uint)target.Height,(uint)width,(uint)height,0,0};
        ((delegate* unmanaged[Stdcall]<IntPtr,IntPtr,uint,IntPtr,void*,uint,uint,void>)Method(context,48))(context,constants,0,IntPtr.Zero,parameters,0,0);
        IntPtr srv=sourceView,uav=outputView,buffer=constants;
        ((delegate* unmanaged[Stdcall]<IntPtr,uint,uint,IntPtr*,void>)Method(context,67))(context,0,1,&srv);
        ((delegate* unmanaged[Stdcall]<IntPtr,uint,uint,IntPtr*,uint*,void>)Method(context,68))(context,0,1,&uav,null);
        ((delegate* unmanaged[Stdcall]<IntPtr,uint,uint,IntPtr*,void>)Method(context,71))(context,0,1,&buffer);
        ((delegate* unmanaged[Stdcall]<IntPtr,IntPtr,IntPtr,uint,void>)Method(context,69))(context,shader,IntPtr.Zero,0);
        ((delegate* unmanaged[Stdcall]<IntPtr,uint,uint,uint,void>)Method(context,41))(context,(uint)(width+15)/16,(uint)(height+15)/16,1);
        srv=IntPtr.Zero;uav=IntPtr.Zero;
        ((delegate* unmanaged[Stdcall]<IntPtr,uint,uint,IntPtr*,void>)Method(context,67))(context,0,1,&srv);
        ((delegate* unmanaged[Stdcall]<IntPtr,uint,uint,IntPtr*,uint*,void>)Method(context,68))(context,0,1,&uav,null);
        if(slot.Texture==IntPtr.Zero||slot.Width!=width||slot.Height!=height){Release(ref slot.Texture);slot.Texture=Texture(width,height,28,3,0,0x20000);}
        slot.Width=width;slot.Height=height;slot.SourceWidth=sourceWidth;slot.SourceHeight=sourceHeight;
        slot.Target=target;slot.Captured=captured;slot.Callback=callback;slot.Submitted=submitted;slot.Order=++order;lastTarget=target;
        ((delegate* unmanaged[Stdcall]<IntPtr,IntPtr,IntPtr,void>)Method(context,47))(context,slot.Texture,outputTexture);
        ((delegate* unmanaged[Stdcall]<IntPtr,void>)Method(context,111))(context);
    }
    internal bool TryRead(out Readback? readback){lock(sync){
        readback=null;if(closed)return false;
        var slot=slots.Where(s=>s.Order!=0).MinBy(s=>s.Order);if(slot==null)return false;
        Mapped mapped;int hr=((delegate* unmanaged[Stdcall]<IntPtr,IntPtr,uint,uint,uint,Mapped*,int>)Method(context,14))(context,slot.Texture,0,1,0x100000,&mapped);
        if(hr==unchecked((int)0x887A000A))return false;Marshal.ThrowExceptionForHR(hr);
        long elapsed=(long)Stopwatch.GetElapsedTime(slot.Submitted).TotalMicroseconds,start=Stopwatch.GetTimestamp();
        byte[]? bytes=null;
        try{
            int length=checked(slot.Width*slot.Height*4);bytes=ArrayPool<byte>.Shared.Rent(length);
            if(mapped.Data==IntPtr.Zero||mapped.RowPitch<slot.Width*4)throw new InvalidOperationException("Invalid staging bounds");
            fixed(byte* output=bytes)for(int y=0;y<slot.Height;y++)Buffer.MemoryCopy((byte*)mapped.Data+y*mapped.RowPitch,output+y*slot.Width*4,slot.Width*4,slot.Width*4);
            readback=new(bytes,length,slot.SourceWidth,slot.SourceHeight,slot.Target!,slot.Captured,slot.Callback,elapsed,(long)Stopwatch.GetElapsedTime(start).TotalMicroseconds);
            bytes=null;slot.Order=0;return true;
        }finally{
            ((delegate* unmanaged[Stdcall]<IntPtr,IntPtr,uint,void>)Method(context,15))(context,slot.Texture,0);
            if(bytes!=null)ArrayPool<byte>.Shared.Return(bytes);
            if(slot.Order==0&&(slot.Width!=outputWidth||slot.Height!=outputHeight))Release(ref slot.Texture);
            if(slot.Order==0&&deferredTarget!=null){var next=deferredTarget;deferredTarget=null;Dispatch(next.Clamp(sourceWidth,sourceHeight));}
        }
    }}
    internal void Upload(byte[] bytes,int width,int height,CaptureTarget target){lock(sync){
        if(!CaptureBounds.Valid(width,height)||bytes.Length!=checked(width*height*4)||target!=target.Clamp(width,height))throw new ArgumentException("Invalid GPU test vector");
        Source(width,height);fixed(byte* data=bytes)((delegate* unmanaged[Stdcall]<IntPtr,IntPtr,uint,IntPtr,void*,uint,uint,void>)Method(context,48))(context,sourceTexture,0,IntPtr.Zero,data,(uint)width*4,(uint)bytes.Length);
        Dispatch(target);
    }}
    internal void VerifyBoundedBurst(){
        long before=Replaced;
        for(int i=0;i<100;i++){
            int w=11+i%7,h=9+(i*3)%11;var data=new byte[w*h*4];Array.Fill(data,(byte)i);
            Upload(data,w,h,new CaptureTarget(0,0,w,h,6,5));
        }
        Retarget(new CaptureTarget(0,0,12,9,5,4));
        Retarget(new CaptureTarget(0,0,12,9,6,5));
        var clock=Stopwatch.StartNew();int count=0;Readback? last=null;
        while(Pending){
            if(clock.Elapsed.TotalSeconds>10)throw new TimeoutException("GPU burst stalled");
            if(TryRead(out var next)){
                if(last!=null)ArrayPool<byte>.Shared.Return(last.Bytes);
                last=next;count++;
            }else Thread.Sleep(1);
        }
        try{
            if(count!=4||Replaced-before!=99||last==null||last.SourceWidth!=12||last.SourceHeight!=9||last.Target.OutWidth!=6||last.Target.OutHeight!=5)throw new InvalidOperationException("Unbounded GPU ring");
            for(int i=0;i<last.Length;i++)if(last.Bytes[i]!=(i%4==3?255:99))throw new InvalidOperationException("Stale GPU burst frame");
        }finally{if(last!=null)ArrayPool<byte>.Shared.Return(last.Bytes);}
    }
    public void Dispose(){lock(sync){
        if(closed)return;closed=true;
        foreach(var slot in slots)Release(ref slot.Texture);
        Release(ref outputView);Release(ref outputTexture);Release(ref sourceView);Release(ref sourceTexture);
        Release(ref constants);Release(ref shader);Release(ref context);Release(ref device);
    }}
    const string Shader="""
        Texture2D<float4> Source : register(t0);
        RWTexture2D<float4> Output : register(u0);
        cbuffer Target : register(b0) { uint2 origin; uint2 size; uint2 outputSize; uint2 padding; };
        [numthreads(16,16,1)]
        void main(uint3 id : SV_DispatchThreadID) {
            if(any(id.xy >= outputSize)) return;
            uint2 lo=id.xy*size, hi=(id.xy+1)*size;
            uint2 first=lo/outputSize, last=(hi+outputSize-1)/outputSize;
            uint3 sum=0;
            for(uint y=first.y;y<last.y;y++) {
                uint wy=min(hi.y,(y+1)*outputSize.y)-max(lo.y,y*outputSize.y);
                for(uint x=first.x;x<last.x;x++) {
                    uint wx=min(hi.x,(x+1)*outputSize.x)-max(lo.x,x*outputSize.x);
                    uint3 rgb=(uint3)round(Source.Load(int3(origin+uint2(x,y),0)).rgb*255.0);
                    sum+=rgb*wx*wy;
                }
            }
            uint denominator=size.x*size.y;
            uint3 result=(sum+denominator/2)/denominator;
            Output[id.xy]=float4(result/255.0,1.0);
        }
        """;
}
