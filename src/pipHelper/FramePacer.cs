// Bound average conversion work without dropping a native-rate source for ordinary callback jitter.
internal sealed class FramePacer {
    readonly long period;
    long deadline;
    internal FramePacer(int fps,long frequency){period=frequency/fps;}
    internal bool Accept(long now){
        if(deadline!=0&&now<deadline-period/2)return false;
        deadline=deadline==0||now-deadline>period*2?now+period:deadline+period;
        return true;
    }
    internal static bool SelfTest(){
        foreach(int fps in new[]{15,24,144,165,240,360,480}){
            var high=new FramePacer(fps,1_000_000);int count=0;
            for(int i=0;i<fps*2;i++)if(high.Accept(1_000_000+(long)(i*1_000_000.0/fps)))count++;
            if(count!=fps*2)return false;
        }
        var native=new FramePacer(60,1_000_000);int accepted=0;
        for(int i=0;i<120;i++)if(native.Accept(1_000_000+(long)(i*1_000_000.0/60)+(i%2==0?0:-3500)))accepted++;
        if(accepted!=120)return false;
        var fast=new FramePacer(60,1_000_000);accepted=0;
        for(int i=0;i<288;i++)if(fast.Accept(1_000_000+(long)(i*1_000_000.0/144)))accepted++;
        if(accepted<119||accepted>121)return false;
        var slow=new FramePacer(60,1_000_000);accepted=0;
        for(int i=0;i<60;i++)if(slow.Accept(1_000_000+(long)(i*1_000_000.0/30)))accepted++;
        return accepted==60;
    }
}
