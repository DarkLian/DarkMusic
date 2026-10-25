using System.Collections.Concurrent;
using System.Diagnostics;

// Explicit developer diagnostic only. Bounded records, background disk I/O, no image/audio data.
internal sealed class CaptureTrace : IDisposable {
    readonly ConcurrentQueue<Sample> pending=new();
    readonly Task writer;
    int count;long dropped;volatile bool closed;
    readonly record struct Sample(string Stage,long Callback,long Sequence,long Qpc,long Source100ns,long Value);
    internal static CaptureTrace? Create(){var path=Environment.GetEnvironmentVariable("DARKMUSIC_PIP_TRACE");return string.IsNullOrWhiteSpace(path)?null:new(path);}
    CaptureTrace(string path){writer=Task.Run(async()=>{
        try {
            Directory.CreateDirectory(path);
            using var output=new StreamWriter(Path.Combine(path,$"native-{Environment.ProcessId}.csv"));
            output.WriteLine("stage,callback,sequence,qpc,source100ns,value");
            while(!closed||!pending.IsEmpty){
                while(pending.TryDequeue(out var x)){Interlocked.Decrement(ref count);output.WriteLine($"{x.Stage},{x.Callback},{x.Sequence},{x.Qpc},{x.Source100ns},{x.Value}");}
                await output.FlushAsync();if(!closed)await Task.Delay(100);
            }
            output.WriteLine($"summary,0,0,{Stopwatch.GetTimestamp()},0,{Interlocked.Read(ref dropped)}");
        }catch{ /* Diagnostics never terminate capture. */ }
    });}
    internal void Add(string stage,long callback,long sequence,long source100ns=0,long value=0){
        if(closed)return;
        if(Interlocked.Increment(ref count)>4096){Interlocked.Decrement(ref count);Interlocked.Increment(ref dropped);return;}
        pending.Enqueue(new(stage,callback,sequence,Stopwatch.GetTimestamp(),source100ns,value));
    }
    public void Dispose(){closed=true;try{writer.Wait(TimeSpan.FromSeconds(1));}catch{}}
}
