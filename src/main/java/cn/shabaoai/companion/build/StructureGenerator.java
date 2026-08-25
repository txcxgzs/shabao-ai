/*
 * Copyright (C) 2026 txcxgzs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package cn.shabaoai.companion.build;

import net.minecraft.block.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.*;

public final class StructureGenerator {
    private StructureGenerator() {}

    public static BuildPlan generate(String structure, String material, int size, BlockPos origin) {
        int s = Math.max(5, Math.min(24, size));
        List<BuildPlan.Placement> out = new ArrayList<>();
        switch (structure.toLowerCase(Locale.ROOT)) {
            case "tower" -> tower(out, origin, s, palette(material));
            case "temple" -> temple(out, origin, s, palette(material));
            case "tree" -> tree(out, origin, s);
            default -> house(out, origin, s, palette(material));
        }
        out.sort(Comparator.comparingInt((BuildPlan.Placement p) -> p.pos().getY())
                .thenComparingInt(p -> distance2(origin, p.pos())));
        BlockPos min = out.stream().map(BuildPlan.Placement::pos).reduce(origin, (a,b) -> new BlockPos(Math.min(a.getX(),b.getX()),Math.min(a.getY(),b.getY()),Math.min(a.getZ(),b.getZ())));
        BlockPos max = out.stream().map(BuildPlan.Placement::pos).reduce(origin, (a,b) -> new BlockPos(Math.max(a.getX(),b.getX()),Math.max(a.getY(),b.getY()),Math.max(a.getZ(),b.getZ())));
        return new BuildPlan(structure, min, max, List.copyOf(out));
    }

    private static void house(List<BuildPlan.Placement> o, BlockPos p, int s, Palette m) {
        int h = Math.max(4, s / 2), d = s;
        for (int x=0;x<s;x++) for(int z=0;z<d;z++) add(o,p,x,0,z,m.foundation);
        for (int y=1;y<=h;y++) for(int x=0;x<s;x++) for(int z=0;z<d;z++) {
            boolean wall=x==0||z==0||x==s-1||z==d-1;
            boolean door=z==0 && (x==s/2||x==s/2-1) && y<=2;
            boolean window=wall && y==2 && ((x==0||x==s-1) ? z%4==2 : x%4==2);
            if (wall && !door) add(o,p,x,y,z,window?Blocks.GLASS_PANE.getDefaultState():m.wall);
        }
        int roofY=h+1;
        for(int layer=0;layer<=d/2;layer++) for(int x=-1;x<=s;x++) {
            add(o,p,x,roofY+layer,layer-1,roofFacing(m.roof,Direction.SOUTH));
            add(o,p,x,roofY+layer,d-layer,roofFacing(m.roof,Direction.NORTH));
        }
        add(o,p,s/2,1,d-1,Blocks.TORCH.getDefaultState());
    }

    private static void tower(List<BuildPlan.Placement> o, BlockPos p, int s, Palette m) {
        int r=Math.max(3,s/2), h=Math.max(10,s*2);
        for(int y=0;y<=h;y++) for(int x=-r;x<=r;x++) for(int z=-r;z<=r;z++) {
            double dist=Math.sqrt(x*x+z*z);
            if (y==0 || (dist>=r-0.75&&dist<=r+0.25)) add(o,p,x,y,z,m.foundation);
        }
        for(int x=-r-1;x<=r+1;x++) for(int z=-r-1;z<=r+1;z++) add(o,p,x,h+1,z,m.wall);
        for(int i=-r-1;i<=r+1;i+=2) { add(o,p,i,h+2,-r-1,m.roof); add(o,p,i,h+2,r+1,m.roof); add(o,p,-r-1,h+2,i,m.roof); add(o,p,r+1,h+2,i,m.roof); }
    }

    private static void temple(List<BuildPlan.Placement> o, BlockPos p, int s, Palette m) {
        int levels=Math.max(3,s/4);
        for(int level=0;level<levels;level++) {
            int min=level, max=s-level;
            for(int x=min;x<=max;x++) for(int z=min;z<=max;z++) add(o,p,x,level,z,m.foundation);
        }
        int y=levels;
        for(int x=2;x<=s-2;x+=Math.max(3,s/4)) for(int z=2;z<=s-2;z+=Math.max(3,s/4))
            for(int dy=0;dy<5;dy++) add(o,p,x,y+dy,z,m.wall);
        for(int x=1;x<s;x++) for(int z=1;z<s;z++) add(o,p,x,y+5,z,m.roof);
    }

    private static void tree(List<BuildPlan.Placement> o, BlockPos p, int s) {
        int h=Math.max(5,s);
        for(int y=0;y<h;y++) add(o,p,0,y,0,Blocks.OAK_LOG.getDefaultState());
        for(int y=h-3;y<=h+1;y++) { int r=y==h+1?1:2; for(int x=-r;x<=r;x++) for(int z=-r;z<=r;z++) if(Math.abs(x)+Math.abs(z)<=r+1) add(o,p,x,y,z,Blocks.OAK_LEAVES.getDefaultState()); }
    }

    private static Palette palette(String material) {
        return switch (material.toLowerCase(Locale.ROOT)) {
            case "stone" -> new Palette(Blocks.COBBLESTONE.getDefaultState(), Blocks.STONE_BRICKS.getDefaultState(), Blocks.STONE_BRICK_STAIRS.getDefaultState());
            case "brick" -> new Palette(Blocks.STONE_BRICKS.getDefaultState(), Blocks.BRICKS.getDefaultState(), Blocks.BRICK_STAIRS.getDefaultState());
            default -> new Palette(Blocks.COBBLESTONE.getDefaultState(), Blocks.OAK_PLANKS.getDefaultState(), Blocks.OAK_STAIRS.getDefaultState());
        };
    }
    private static BlockState roofFacing(BlockState state,Direction direction){return state.getBlock() instanceof StairsBlock?state.with(StairsBlock.FACING,direction):state;}
    private static void add(List<BuildPlan.Placement> o, BlockPos p, int x,int y,int z,BlockState s){ o.add(new BuildPlan.Placement(p.add(x,y,z),s)); }
    private static int distance2(BlockPos a,BlockPos b){int x=a.getX()-b.getX(),z=a.getZ()-b.getZ();return x*x+z*z;}
    private record Palette(BlockState foundation, BlockState wall, BlockState roof) {}
}
