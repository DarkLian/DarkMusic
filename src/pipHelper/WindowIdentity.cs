using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

internal sealed record WindowIdentity(long Handle, int Pid, long CreatedTicks, string Title, string App, bool Minimized) {
    internal bool Valid() {
        try {
            Native.GetWindowThreadProcessId((IntPtr)Handle,out var pid);
            using var process=Process.GetProcessById(Pid);
            return Native.IsWindow((IntPtr)Handle)&&pid==Pid&&process.StartTime.ToUniversalTime().Ticks==CreatedTicks;
        }catch{return false;}
    }
    internal static List<WindowIdentity> List(){
        var result=new List<WindowIdentity>();
        EnumWindows((window,_)=>{
            if(result.Count>=256)return false;
            if(!IsWindowVisible(window)||GetWindow(window,4)!=IntPtr.Zero || window==GetShellWindow() || (GetWindowLongPtr(window,-20).ToInt64() & 0x08000080L)!=0)return true;
            if(DwmGetWindowAttribute(window,14,out int cloaked,4)==0&&cloaked!=0)return true;
            var title=new StringBuilder(513);GetWindowText(window,title,513);
            if(title.Length==0)return true;
            Native.GetWindowThreadProcessId(window,out var pid);
            try{
                using var process=Process.GetProcessById((int)pid);
                if(pid==Environment.ProcessId)return true;
                result.Add(new(window.ToInt64(),(int)pid,process.StartTime.ToUniversalTime().Ticks,title.ToString(),process.ProcessName,Native.IsIconic(window)));
            }catch{ /* Inaccessible/elevated windows are not offered. */ }
            return true;
        },IntPtr.Zero);
        return result;
    }
    delegate bool WindowCallback(IntPtr hwnd,IntPtr state);
    [DllImport("user32.dll")]static extern bool EnumWindows(WindowCallback callback,IntPtr state);
    [DllImport("user32.dll")]static extern bool IsWindowVisible(IntPtr hwnd);
    [DllImport("user32.dll")]static extern IntPtr GetShellWindow();
    [DllImport("user32.dll",EntryPoint="GetWindowLongPtrW")]static extern IntPtr GetWindowLongPtr(IntPtr hwnd,int index);
    [DllImport("user32.dll")]static extern IntPtr GetWindow(IntPtr hwnd,uint command);
    [DllImport("user32.dll",CharSet=CharSet.Unicode)]static extern int GetWindowText(IntPtr hwnd,StringBuilder text,int maximum);
    [DllImport("dwmapi.dll")]static extern int DwmGetWindowAttribute(IntPtr hwnd,int attribute,out int value,int size);
}
