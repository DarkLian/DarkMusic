using System.Diagnostics;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text.Json;
using Windows.Graphics.Capture;
using Windows.Graphics.DirectX;
using Windows.Graphics.DirectX.Direct3D11;
using Windows.Graphics.Imaging;
using Windows.Storage.Streams;

internal static class Native {
    [DllImport("user32.dll")] internal static extern uint GetWindowThreadProcessId(IntPtr hwnd,out uint pid);
    [DllImport("user32.dll", SetLastError=true)] internal static extern bool RegisterHotKey(IntPtr hwnd,int id,uint mods,uint key);
    [DllImport("user32.dll")] internal static extern bool UnregisterHotKey(IntPtr hwnd,int id);
    [DllImport("user32.dll")] internal static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] internal static extern bool SetForegroundWindow(IntPtr hwnd);
    [DllImport("user32.dll")] internal static extern bool ShowWindow(IntPtr hwnd,int cmd);
    [DllImport("user32.dll")] internal static extern bool IsIconic(IntPtr hwnd);
    [DllImport("user32.dll")] internal static extern bool IsWindow(IntPtr hwnd);
    [DllImport("d3d11.dll")] internal static extern int D3D11CreateDevice(IntPtr adapter,uint type,IntPtr software,uint flags,IntPtr levels,uint count,uint sdk,out IntPtr device,out uint level,out IntPtr context);
    [DllImport("d3d11.dll")] internal static extern int CreateDirect3D11DeviceFromDXGIDevice(IntPtr dxgi,out IntPtr device);
    [DllImport("combase.dll",CharSet=CharSet.Unicode)] internal static extern int WindowsCreateString(string text,uint len,out IntPtr value);
    [DllImport("combase.dll")] internal static extern int WindowsDeleteString(IntPtr value);
    [DllImport("combase.dll")] internal static extern int RoGetActivationFactory(IntPtr name,ref Guid iid,out IntPtr factory);
    [ComImport,Guid("3628E81B-3CAC-4C60-B7F4-23CE0E0C3356"),InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface CaptureInterop {
        [PreserveSig] int CreateForWindow(IntPtr hwnd,ref Guid iid,out IntPtr item);
        [PreserveSig] int CreateForMonitor(IntPtr monitor,ref Guid iid,out IntPtr item);
    }
    internal static IDirect3DDevice Device() {
        Marshal.ThrowExceptionForHR(D3D11CreateDevice(IntPtr.Zero,1,IntPtr.Zero,0x20,IntPtr.Zero,0,7,out var d,out _,out var c));
        IntPtr dxgi=IntPtr.Zero,p=IntPtr.Zero;
        try {
            var iid=new Guid("54ec77fa-1377-44e6-8c32-88fd5f44c84c");
            Marshal.ThrowExceptionForHR(Marshal.QueryInterface(d,ref iid,out dxgi));
            Marshal.ThrowExceptionForHR(CreateDirect3D11DeviceFromDXGIDevice(dxgi,out p));
            return WinRT.MarshalInterface<IDirect3DDevice>.FromAbi(p);
        } finally { if(p!=IntPtr.Zero)Marshal.Release(p);if(dxgi!=IntPtr.Zero)Marshal.Release(dxgi);Marshal.Release(d);Marshal.Release(c); }
    }
    internal static GraphicsCaptureItem Item(IntPtr hwnd) {
        const string name="Windows.Graphics.Capture.GraphicsCaptureItem";
        Marshal.ThrowExceptionForHR(WindowsCreateString(name,(uint)name.Length,out var text));
        IntPtr factory=IntPtr.Zero,p=IntPtr.Zero;
        try {
            var iid=typeof(CaptureInterop).GUID;
            Marshal.ThrowExceptionForHR(RoGetActivationFactory(text,ref iid,out factory));
            var interop=(CaptureInterop)Marshal.GetObjectForIUnknown(factory);
            var itemId=new Guid("79c3f95b-31f7-4ec2-a464-632ef5d30760");
            Marshal.ThrowExceptionForHR(interop.CreateForWindow(hwnd,ref itemId,out p));
            return WinRT.MarshalInterface<GraphicsCaptureItem>.FromAbi(p);
        } finally {if(p!=IntPtr.Zero)Marshal.Release(p);if(factory!=IntPtr.Zero)Marshal.Release(factory);WindowsDeleteString(text);}
    }
}
