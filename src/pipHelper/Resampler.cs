using System.Numerics;
using System.Runtime.Intrinsics;
using System.Runtime.Intrinsics.X86;

// Integer pixel coverage in sRGB. Coordinates use output-size units, so each horizontal
// weight sums to cropWidth and each vertical weight to cropHeight. One final half-up
// division by cropWidth*cropHeight; no intermediate rounding and no upscaling.
internal sealed record CaptureTarget(int X,int Y,int Width,int Height,int OutWidth,int OutHeight) {
    internal CaptureTarget Clamp(int width,int height){
        int x=Math.Clamp(X,0,width-1),y=Math.Clamp(Y,0,height-1);
        int w=Math.Clamp(Width,1,width-x),h=Math.Clamp(Height,1,height-y);
        return new(x,y,w,h,Math.Clamp(OutWidth,1,w),Math.Clamp(OutHeight,1,h));
    }
    internal static CaptureTarget? Parse(string command){
        var parts=command.Split(' ',StringSplitOptions.RemoveEmptyEntries);
        if(parts.Length!=7||parts[0]!="TARGET")return null;
        var values=new int[6];
        for(int i=0;i<6;i++)if(!int.TryParse(parts[i+1],out values[i])||values[i]<0||values[i]>7680)return null;
        return values[2]>0&&values[3]>0&&values[4]>0&&values[5]>0?new(values[0],values[1],values[2],values[3],values[4],values[5]):null;
    }
}

internal sealed class Resampler {
    int cachedWidth,cachedOutputWidth;
    int[] horizontal=Array.Empty<int>(),accumulated=Array.Empty<int>();
    Coverage[] coverage=Array.Empty<Coverage>();
    void Prepare(int width,int outputWidth){
        if(cachedWidth==width&&cachedOutputWidth==outputWidth)return;
        cachedWidth=width;cachedOutputWidth=outputWidth;
        horizontal=new int[outputWidth*4];accumulated=new int[horizontal.Length];coverage=new Coverage[outputWidth];
        for(int x=0;x<outputWidth;x++)coverage[x]=Coverage.For(x,width,outputWidth);
    }
    internal unsafe void Copy(byte* source,int stride,Span<byte> output,CaptureTarget t){
        if(t.OutWidth>t.Width||t.OutHeight>t.Height||output.Length!=checked(t.OutWidth*t.OutHeight*4))throw new ArgumentException("Invalid resample bounds");
        Prepare(t.Width,t.OutWidth);
        var row=horizontal;var sums=accumulated;var columns=coverage;int cached=-1;
        int denominator=checked(t.Width*t.Height);
        ulong reciprocal=((1UL<<32)+(uint)denominator-1)/(uint)denominator;
        for(int dy=0;dy<t.OutHeight;dy++){
            Array.Clear(sums);
            int top=dy*t.Height,bottom=(dy+1)*t.Height;
            for(int sy=top/t.OutHeight;sy<(bottom+t.OutHeight-1)/t.OutHeight;sy++){
                if(cached!=sy){Horizontal(source+(t.Y+sy)*stride+t.X*4,row,columns,t.OutWidth);cached=sy;}
                int weight=Math.Min(bottom,(sy+1)*t.OutHeight)-Math.Max(top,sy*t.OutHeight);
                int i=0;
                if(Vector.IsHardwareAccelerated){
                    var weights=new Vector<int>(weight);
                    for(;i+Vector<int>.Count<=row.Length;i+=Vector<int>.Count){
                        var sum=new Vector<int>(sums,i)+new Vector<int>(row,i)*weights;
                        sum.CopyTo(sums,i);
                    }
                }
                for(;i<row.Length;i++)sums[i]+=row[i]*weight;
            }
            for(int dx=0;dx<t.OutWidth;dx++){
                int dst=(dy*t.OutWidth+dx)*4;
                for(int c=0;c<3;c++)output[dst+c]=Divide(sums[dx*4+2-c],denominator,reciprocal);
                output[dst+3]=255;
            }
        }
    }
    // The upward-rounded reciprocal can overshoot by at most one for this bounded
    // numerator. Correct that case using integer arithmetic, keeping division exact.
    static byte Divide(int value,int denominator,ulong reciprocal){
        uint numerator=(uint)(value+denominator/2);
        uint quotient=(uint)(((ulong)numerator*reciprocal)>>32);
        if((ulong)quotient*(uint)denominator>numerator)quotient--;
        return (byte)quotient;
    }
    readonly record struct Coverage(int Start,int End,int First,int Last){
        internal static Coverage For(int x,int width,int output){
            int left=x*width,right=(x+1)*width,start=left/output,end=(right-1)/output;
            return new(start,end,Math.Min(right,(start+1)*output)-left,right-Math.Max(left,end*output));
        }
    }
    static unsafe Vector128<int> Pixel(byte* p)=>Sse41.ConvertToVector128Int32(Vector128.CreateScalar(*(uint*)p).AsByte());
    static unsafe void Horizontal(byte* src,int[] row,Coverage[] columns,int outputWidth){
        fixed(int* output=row)for(int x=0;x<outputWidth;x++){
            var c=columns[x];
            if(Sse41.IsSupported){
                var sum=Sse41.MultiplyLow(Pixel(src+c.Start*4),Vector128.Create(c.First));
                if(c.End>c.Start){
                    var middle=Vector128<int>.Zero;
                    for(int sx=c.Start+1;sx<c.End;sx++)middle=Sse2.Add(middle,Pixel(src+sx*4));
                    sum=Sse2.Add(sum,Sse41.MultiplyLow(middle,Vector128.Create(outputWidth)));
                    sum=Sse2.Add(sum,Sse41.MultiplyLow(Pixel(src+c.End*4),Vector128.Create(c.Last)));
                }
                Sse2.Store(output+x*4,sum);
            }else{
                for(int channel=0;channel<3;channel++){
                    int sum=src[c.Start*4+channel]*c.First;
                    if(c.End>c.Start){
                        for(int sx=c.Start+1;sx<c.End;sx++)sum+=src[sx*4+channel]*outputWidth;
                        sum+=src[c.End*4+channel]*c.Last;
                    }
                    row[x*4+channel]=sum;
                }
            }
        }
    }
    internal static unsafe bool SelfTest(){
        byte[] src={0,0,0,9,255,255,255,0,0,0,255,0,255,0,0,0};
        byte[] dst=new byte[4];
        fixed(byte* p=src)new Resampler().Copy(p,8,dst,new(0,0,2,2,1,1));
        return dst.AsSpan().SequenceEqual(new byte[]{128,64,128,255});
    }
}
