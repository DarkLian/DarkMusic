using System.ComponentModel;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

// A process-local high-resolution wait. Task.Delay(1) can wake in 15.6 ms steps on Windows;
// changing the system timer resolution would affect other applications.
internal sealed class CaptureWait : IDisposable {
    readonly SafeWaitHandle timer=CreateWaitableTimerExW(IntPtr.Zero,null,2,0x100002);
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true,ExactSpelling=true)]
    static extern SafeWaitHandle CreateWaitableTimerExW(IntPtr attributes,string? name,uint flags,uint access);
    [DllImport("kernel32.dll",SetLastError=true,ExactSpelling=true)]
    [return:MarshalAs(UnmanagedType.Bool)]
    static extern bool SetWaitableTimer(SafeWaitHandle timer,ref long due,int period,IntPtr callback,IntPtr argument,[MarshalAs(UnmanagedType.Bool)]bool resume);
    [DllImport("kernel32.dll",SetLastError=true,ExactSpelling=true)]
    static extern uint WaitForSingleObject(SafeWaitHandle handle,uint milliseconds);
    internal CaptureWait(){if(timer.IsInvalid)throw new Win32Exception(Marshal.GetLastWin32Error());}
    internal void Once(){
        long due=-10_000;
        if(!SetWaitableTimer(timer,ref due,0,IntPtr.Zero,IntPtr.Zero,false))throw new Win32Exception(Marshal.GetLastWin32Error());
        uint result=WaitForSingleObject(timer,100);
        if(result!=0)throw new InvalidOperationException("Capture timer failed");
    }
    public void Dispose()=>timer.Dispose();
}
