// A failed optional audio route must never stop video or accumulate packets/workers.
internal static class AudioRecovery {
    internal static async Task Run(Func<CancellationToken,Task<bool>> attempt,CancellationToken stop,Action<string,object> report,Func<int,CancellationToken,Task>? delay=null){
        delay??=((number,token)=>Task.Delay(TimeSpan.FromSeconds(number),token));
        for(int number=0;number<3&&!stop.IsCancellationRequested;number++){
            try{
                if(number>0)await delay(number,stop);
                if(stop.IsCancellationRequested)return;
                // true denotes a permanent unsupported capability, not a transient route failure.
                if(await attempt(stop)||stop.IsCancellationRequested)return;
            }catch(OperationCanceledException)when(stop.IsCancellationRequested){return;}
            catch(Exception error){if(stop.IsCancellationRequested)return;report("audio_retry",new{code=error.HResult,stage="setup"});}
            if(number<2)report("audio_retry",new{attempt=number+2});
        }
        if(!stop.IsCancellationRequested)report("audio_error",new{stage="retries_exhausted"});
    }
}

internal static class CaptureBounds {
    internal static bool Matches(int surfaceWidth,int surfaceHeight,int width,int height)=>surfaceWidth==width&&surfaceHeight==height;
}
