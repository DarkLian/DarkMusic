using System.Runtime.InteropServices;
using Windows.Foundation.Metadata;
using Windows.Graphics.Capture;

// Runtime-gated ABI access preserves the helper's existing Windows 19041 build target.
// IGraphicsCaptureSession5: Microsoft win32metadata windows.graphics.capture.h.
internal static class CaptureInterval {
    internal sealed record Result(bool Supported,long BeforeTicks,long EffectiveTicks,int Error);
    // Half the faster of the limit and source monitor rate. Otherwise a 60 limit on a 144 Hz source
    // captures roughly 72 updates and then drops more in the pacer, creating 28 ms capture gaps.
    // Never slower than Windows' default of 16 ms, and never below 1 ms.
    internal static long TicksFor(int frameRate,int sourceRefreshRate=0)=>Math.Clamp((long)Math.Round(5_000_000.0/Math.Max(frameRate,sourceRefreshRate)),10_000L,160_000L);
    [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)]struct DisplayMode {
        [MarshalAs(UnmanagedType.ByValTStr,SizeConst=32)]internal string DeviceName;
        internal ushort SpecVersion,DriverVersion,Size,DriverExtra;internal uint Fields;
        internal int X,Y;internal uint Orientation,FixedOutput;
        internal short Color,Duplex,YResolution,TTOption,Collate;
        [MarshalAs(UnmanagedType.ByValTStr,SizeConst=32)]internal string FormName;
        internal ushort LogPixels;internal uint Bits,Width,Height,Flags,Frequency,ICMMethod,ICMIntent,MediaType,DitherType,Reserved1,Reserved2,PanningWidth,PanningHeight;
    }
    [DllImport("user32.dll",CharSet=CharSet.Unicode,ExactSpelling=true)]
    [return:MarshalAs(UnmanagedType.Bool)]static extern bool EnumDisplaySettingsW(string device,int mode,ref DisplayMode value);
    internal static int SourceRefreshRate(IntPtr window){
        try{
            var mode=new DisplayMode{DeviceName="",FormName="",Size=(ushort)Marshal.SizeOf<DisplayMode>()};
            if(EnumDisplaySettingsW(Screen.FromHandle(window).DeviceName,-1,ref mode)&&mode.Frequency>=15&&mode.Frequency<=1000)return (int)mode.Frequency;
        }catch{ /* Unknown display modes keep the existing interval calculation. */ }
        return 0;
    }
    internal static unsafe Result Inspect(GraphicsCaptureSession session,long? ticks) {
        if(!ApiInformation.IsPropertyPresent("Windows.Graphics.Capture.GraphicsCaptureSession","MinUpdateInterval"))
            return new(false,-1,-1,0);
        IntPtr inspect=IntPtr.Zero,extension=IntPtr.Zero;
        try {
            inspect=WinRT.MarshalInspectable<GraphicsCaptureSession>.FromManaged(session);
            var iid=new Guid("67c0ea62-1f85-5061-925a-239be0ac09cb");
            Marshal.ThrowExceptionForHR(Marshal.QueryInterface(inspect,ref iid,out extension));
            var table=Marshal.ReadIntPtr(extension);
            var get=(delegate* unmanaged[Stdcall]<IntPtr,long*,int>)Marshal.ReadIntPtr(table,6*IntPtr.Size);
            var set=(delegate* unmanaged[Stdcall]<IntPtr,long,int>)Marshal.ReadIntPtr(table,7*IntPtr.Size);
            long before=0,after=0;
            Marshal.ThrowExceptionForHR(get(extension,&before));
            if(ticks.HasValue)Marshal.ThrowExceptionForHR(set(extension,ticks.Value));
            Marshal.ThrowExceptionForHR(get(extension,&after));
            return new(true,before,after,0);
        } catch(Exception ex) { return new(true,-1,-1,ex.HResult); }
        finally { if(extension!=IntPtr.Zero)Marshal.Release(extension);if(inspect!=IntPtr.Zero)Marshal.Release(inspect);GC.KeepAlive(session); }
    }
}
