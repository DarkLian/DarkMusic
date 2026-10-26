using System.IO.Pipes;

// Explicit local/CI self-test only; never started by ordinary capture.
internal static class CaptureReliabilityTests {
    internal static bool Run(){
        if(!CaptureBounds.Valid(5120,1440)||!CaptureBounds.Valid(2160,3840)||CaptureBounds.Valid(5120,2160)||CaptureBounds.Valid(7681,1))return false;
        if(!CaptureBounds.Matches(64,48,64,48)||CaptureBounds.Matches(32,24,64,48)||CaptureBounds.Matches(128,96,64,48))return false;
        if(CaptureInterval.TicksFor(144)!=34_722||CaptureInterval.TicksFor(60)!=83_333||CaptureInterval.TicksFor(15)!=160_000||CaptureInterval.TicksFor(480)!=10_417)return false;
        var reports=new List<string>();int calls=0;
        Func<int,CancellationToken,Task> noWait=(_,_)=>Task.CompletedTask;
        AudioRecovery.Run(_=>{calls++;throw new IOException("injected audio failure");},CancellationToken.None,(kind,_)=>reports.Add(kind),noWait).GetAwaiter().GetResult();
        if(calls!=3||reports.Count(kind=>kind=="audio_error")!=1)return false;
        calls=0;reports.Clear();
        AudioRecovery.Run(_=>Task.FromResult(++calls==3),CancellationToken.None,(kind,_)=>reports.Add(kind),noWait).GetAwaiter().GetResult();
        if(calls!=3||reports.Contains("audio_error"))return false;
        using var stop=new CancellationTokenSource();calls=0;reports.Clear();
        AudioRecovery.Run(_=>{calls++;stop.Cancel();return Task.FromResult(false);},stop.Token,(kind,_)=>reports.Add(kind),noWait).GetAwaiter().GetResult();
        if(calls!=1||reports.Count!=0)return false;
        // A genuine pipe-name collision must be contained by the optional-audio supervisor.
        string name="darkmusic-audio-test-"+Guid.NewGuid();
        using(var occupied=new NamedPipeServerStream(name,PipeDirection.Out,1)){
            calls=0;reports.Clear();
            AudioRecovery.Run(async token=>{calls++;using var audio=new ProcessAudio(0,name,token,(_,_)=>{});await audio.Completion;return true;},CancellationToken.None,(kind,_)=>reports.Add(kind),noWait).GetAwaiter().GetResult();
            if(calls!=3||!reports.Contains("audio_error"))return false;
        }
        // Exercise the real WinForms exception route without a capture source or error dialog.
        using var form=new Form{ShowInTaskbar=false,Opacity=0};int failures=0;
        form.Load+=(_,_)=>form.BeginInvoke(()=>throw new InvalidOperationException("injected UI failure"));
        CaptureHost.RunMessageLoop(form,()=>{failures++;form.Close();});
        return failures==1;
    }
}
