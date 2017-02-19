package com.lulan.shincolle.tileentity;

import java.util.List;

import com.lulan.shincolle.block.BlockCrane;
import com.lulan.shincolle.block.ItemBlockWaypoint;
import com.lulan.shincolle.capability.CapaInventory;
import com.lulan.shincolle.capability.CapaShipInventory;
import com.lulan.shincolle.client.gui.inventory.ContainerShipInventory;
import com.lulan.shincolle.entity.BasicEntityShip;
import com.lulan.shincolle.handler.ConfigHandler;
import com.lulan.shincolle.init.ModBlocks;
import com.lulan.shincolle.init.ModItems;
import com.lulan.shincolle.init.ModSounds;
import com.lulan.shincolle.item.PointerItem;
import com.lulan.shincolle.network.C2SInputPackets;
import com.lulan.shincolle.network.S2CGUIPackets;
import com.lulan.shincolle.proxy.ClientProxy;
import com.lulan.shincolle.proxy.CommonProxy;
import com.lulan.shincolle.reference.ID;
import com.lulan.shincolle.utility.BlockHelper;
import com.lulan.shincolle.utility.EnchantHelper;
import com.lulan.shincolle.utility.EntityHelper;
import com.lulan.shincolle.utility.LogHelper;
import com.lulan.shincolle.utility.ParticleHelper;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.IFluidContainerItem;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import net.minecraftforge.oredict.OreDictionary;

/**
 * crane function
 * 
 * mode redstone: 0:no signal, 1:emit one pulse on ending, 2:NYI 
 * mode liquid: 0:none, 1:load liquid to ship, 2:unload liquid from ship
 * mode EU: 0:none, 1:load, 2:unload
 */
public class TileEntityCrane extends BasicTileInventory implements ITileWaypoint, ITickable
{

	private int tick, partDelay, modeItem, modeRedstone, tickRedstone, craneTime, modeLiquid,
				modeEnergy, rateLiquid, rateEU;
	private boolean isActive, isPaired, checkMetadata, checkOredict, checkNbt, enabLoad, enabUnload;
	private BlockPos lastPos, nextPos, chestPos;
	
	/** wait mode:
	 *  0: no wait, no item to trans => stop craning
	 *  1: wait forever until inventory full
	 *  2~5: none
	 *  6~15: wait N-5 min
	 *  16~19: wait 15+(N-16)*5 min
	 *  20~22: wait 40+(N-20)*19 min
	 *  23~25: wait 120+(N-23)*60 min
	 */
	private int craneMode;  //mode: 0:no wait, 1:wait forever, 2~5: NYI, 6~N:wait X-5 min
	private static final int[] NOSLOT = new int[] {}; 
	
	//target
	private BasicEntityShip ship;
	private IInventory chest;
	
	//fluid tank
	protected FluidTank tank;
	
	
	public TileEntityCrane()
	{
		super();
		
		//0~8: loading items, 9~17: unloading items
		this.itemHandler = new CapaInventory(18, this);
		this.ship = null;
		this.chest = null;
		this.isActive = false;
		this.isPaired = false;
		this.enabLoad = true;
		this.enabUnload = true;
		this.checkMetadata = false;
		this.checkOredict = false;
		this.checkNbt = false;
		this.craneMode = 0;
		this.tick = 0;
		this.partDelay = 0;
		this.modeItem = 0;
		this.modeRedstone = 0;
		this.modeLiquid = 0;
		this.modeEnergy = 0;
		this.rateLiquid = 0;
		this.rateEU = 0;
		this.tickRedstone = 0;
		this.lastPos = BlockPos.ORIGIN;
		this.nextPos = BlockPos.ORIGIN;
		this.chestPos = BlockPos.ORIGIN;
		
		//tank
		this.tank = new FluidTank(ConfigHandler.tileCrane[0]);
		this.tank.setTileEntity(this);
		
		//EU storage TODO NYI
	}
	
	@Override
	public String getRegName()
	{
		return BlockCrane.TILENAME;
	}
	
	@Override
	public byte getGuiIntID()
	{
		return ID.Gui.CRANE;
	}
	
	@Override
	public byte getPacketID(int type)
	{
		switch (type)
		{
		case 0:
			return S2CGUIPackets.PID.TileCrane;
		}
		
		return -1;
	}
	
	//依照輸出入口設定, 決定漏斗等裝置如何輸出入物品到特定slot中
	//注意: 此設定必須跟getCapability相同以免出現bug
	@Override
	public int[] getSlotsForFace(EnumFacing side)
	{
		return NOSLOT;
	}
	
	//讀取nbt資料
	@Override
    public void readFromNBT(NBTTagCompound nbt)
	{
        super.readFromNBT(nbt);
        
        //load values
        this.isActive = nbt.getBoolean("active");
        this.isPaired = nbt.getBoolean("paired");
        this.enabLoad = nbt.getBoolean("load");
        this.enabUnload = nbt.getBoolean("unload");
        this.checkMetadata = nbt.getBoolean("meta");
        this.checkOredict = nbt.getBoolean("dict");
        this.checkNbt = nbt.getBoolean("nbt");
        this.craneMode = nbt.getInteger("mode");
        this.modeItem = nbt.getInteger("imode");
        this.modeRedstone = nbt.getInteger("rmode");
        this.modeLiquid = nbt.getInteger("lmode");
        this.modeEnergy = nbt.getInteger("emode");
        
        //load tank
        this.tank.readFromNBT(nbt);
        
        //load pos
        int[] pos =  nbt.getIntArray("chestPos");
        if (pos == null || pos.length != 3) this.chestPos = BlockPos.ORIGIN;
        else this.chestPos = new BlockPos(pos[0], pos[1], pos[2]);
        
        pos =  nbt.getIntArray("lastPos");
        if (pos == null || pos.length != 3) this.lastPos = BlockPos.ORIGIN;
        else this.lastPos = new BlockPos(pos[0], pos[1], pos[2]);
        
        pos =  nbt.getIntArray("nextPos");
        if (pos == null || pos.length != 3) this.nextPos = BlockPos.ORIGIN;
        else this.nextPos = new BlockPos(pos[0], pos[1], pos[2]);
    }
	
	//將資料寫進nbt
	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound nbt)
	{
		super.writeToNBT(nbt);
        
		//save values
		nbt.setBoolean("active", this.isActive);
        nbt.setBoolean("paired", this.isPaired);
        nbt.setBoolean("load", this.enabLoad);
        nbt.setBoolean("unload", this.enabUnload);
        nbt.setBoolean("meta", this.checkMetadata);
        nbt.setBoolean("dict", this.checkOredict);
        nbt.setBoolean("nbt", this.checkNbt);
        nbt.setInteger("mode", this.craneMode);
        nbt.setInteger("imode", this.modeItem);
        nbt.setInteger("rmode", this.modeRedstone);
        nbt.setInteger("lmode", this.modeLiquid);
        nbt.setInteger("emode", this.modeEnergy);
        
		//save tank
		this.tank.writeToNBT(nbt);

        //save pos
        if (this.lastPos != null && this.nextPos != null && this.chestPos != null)
        {
        	nbt.setIntArray("chestPos", new int[] {this.chestPos.getX(), this.chestPos.getY(), this.chestPos.getZ()});
        	nbt.setIntArray("lastPos", new int[] {this.lastPos.getX(), this.lastPos.getY(), this.lastPos.getZ()});
        	nbt.setIntArray("nextPos", new int[] {this.nextPos.getX(), this.nextPos.getY(), this.nextPos.getZ()});
        }
        else
        {
        	nbt.setIntArray("chestPos", new int[] {0, 0, 0});
        	nbt.setIntArray("lastPos", new int[] {0, 0, 0});
        	nbt.setIntArray("nextPos", new int[] {0, 0, 0});
        }
        
        return nbt;
	}
	
    @Override
    public boolean hasCapability(Capability<?> capability, EnumFacing facing)
    {
        return capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY || super.hasCapability(capability, facing);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T getCapability(Capability<T> capability, EnumFacing facing)
    {
        if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY && facing != EnumFacing.DOWN) return (T) tank;
        return super.getCapability(capability, facing);
    }
	
	@Override
	public boolean isItemValidForSlot(int slot, ItemStack itemstack)
	{
		return true;
	}
	
	//使用管線/漏斗輸出時呼叫, 不適用於手動置入
	@Override
	public boolean canExtractItem(int slot, ItemStack item, EnumFacing face)
	{
		return false;
	}
	
	//set paired chest
	public void setPairedChest(BlockPos pos, boolean sendPacket)
	{
		TileEntity tile = this.world.getTileEntity(pos);
		
		if (tile instanceof IInventory)
		{
			this.chestPos = pos;
			this.isPaired = true;
			this.chest = (IInventory) tile;
			
			//send chest paired packet
			if (sendPacket && this.world.isRemote)
			{
				CommonProxy.channelI.sendToServer(new C2SInputPackets(C2SInputPackets.PID.Waypoint_Set,
						2, this.world.provider.getDimension(), this.playerUID,
						this.pos.getX(), this.pos.getY(), this.pos.getZ(),
						this.chestPos.getX(), this.chestPos.getY(), this.chestPos.getZ()));
			}
		}
		else
		{
			clearPairedChest();
		}
	}
	
	//get paired chest
	public void checkPairedChest()
	{
		if (this.isPaired)
		{
			//get chest if no chest tile entity
			if (this.chest == null)
			{
				TileEntity tile = this.world.getTileEntity(this.chestPos);
				
				if (tile instanceof IInventory)
				{
					chest = (IInventory) tile;
				}
			}
			
			//check chest valid
			if (this.chest instanceof IInventory && !((TileEntity) this.chest).isInvalid())
			{
				return;
			}
			
			//tile lost, reset
			clearPairedChest();
			sendSyncPacket();
		}
	}
	
	public void clearPairedChest()
	{
		this.chest = null;
		this.isPaired = false;
		this.chestPos = BlockPos.ORIGIN;
	}
	
	//set data from packet data
	public void setSyncData(int[] data)
	{
		if (data != null)
		{
			this.lastPos = new BlockPos(data[0], data[1], data[2]);
			this.nextPos = new BlockPos(data[3], data[4], data[5]);
			setPairedChest(new BlockPos(data[6], data[7], data[8]), false);
		}
	}
	
	@Override
	public void setNextWaypoint(BlockPos pos)
	{
		if (pos != null)
		{
			this.nextPos = pos;
			
			if (this.world.isRemote)
			{
				CommonProxy.channelI.sendToServer(new C2SInputPackets(C2SInputPackets.PID.Waypoint_Set,
						1, this.world.provider.getDimension(), this.playerUID,
						this.pos.getX(), this.pos.getY(), this.pos.getZ(),
						this.nextPos.getX(), this.nextPos.getY(), this.nextPos.getZ()));
			}
		}
	}

	@Override
	public BlockPos getNextWaypoint()
	{
		return this.nextPos;
	}
	
	@Override
	public void setLastWaypoint(BlockPos pos)
	{
		if (pos != null)
		{
			this.lastPos = pos;
			
			if (this.world.isRemote)
			{
				CommonProxy.channelI.sendToServer(new C2SInputPackets(C2SInputPackets.PID.Waypoint_Set,
						0, this.world.provider.getDimension(), this.playerUID,
						this.pos.getX(), this.pos.getY(), this.pos.getZ(),
						this.lastPos.getX(), this.lastPos.getY(), this.lastPos.getZ()));
			}
		}
	}

	@Override
	public BlockPos getLastWaypoint()
	{
		return this.lastPos;
	}
	
	public void setChestWaypoint(BlockPos pos)
	{
		if (pos != null)
		{
			this.chestPos = pos;
		}
	}

	public BlockPos getChestWaypoint()
	{
		return this.chestPos;
	}
	
	@Override
	public void update()
	{
		//server side
		if (!this.world.isRemote)
		{
			boolean update = false;
			this.tick++;
			
			//redstone signal
			if (this.tickRedstone > 0)
			{
				this.tickRedstone--;
				if (this.tickRedstone <= 0)
				{
					this.world.notifyNeighborsOfStateChange(this.pos, ModBlocks.BlockCrane);
				}
			}

			//can work
			if (this.isActive && this.isPaired)
			{
				//check every 16 ticks
				if (this.tick > 64 && (this.tick & 15) == 0)
				{
					//check chest and ship
					checkPairedChest();
					checkCraningShip();
					
					//set redstone tick
					if (this.modeRedstone == 1 && this.ship != null)
					{
						this.tickRedstone = 18;
						this.world.notifyNeighborsOfStateChange(this.pos, ModBlocks.BlockCrane);
					}
					
					//crane <-> chest liquid transfer
					this.applyPreLiquidTransfer(this.modeLiquid);
					
					if (this.chest != null && ship != null)
					{
						//work: 0:load item, 1:unload item, 2:liquid, 3:EU
						boolean[] workDoing = new boolean[4];
						boolean endCraning = false;
						int waitTime = getWaitTimeInMin(this.craneMode) * 1200;
						
						try
						{
							//check item loading
							if (this.enabLoad)
							{
								workDoing[0] = applyItemTransfer(true);
							}
							
							//check item unloading
							if (this.enabUnload)
							{
								workDoing[1] = applyItemTransfer(false);
							}
							
							//check liquid transport
							if (this.modeLiquid != 0 && this.rateLiquid > 0)
							{
								workDoing[2] = applyLiquidTransfer(this.modeLiquid);
							}
							else
							{
								workDoing[2] = false;
							}
							
							//check EU transport
							if (CommonProxy.activeIC2 && this.modeEnergy != 0 && this.rateEU > 0)
							{
								workDoing[3] = applyEnergyTransfer(this.modeEnergy);
							}
							else
							{
								workDoing[3] = false;
							}
							
							//add exp to transport ship, every work +X exp to ship
							if (this.ship != null && this.ship.getShipType() == ID.ShipType.TRANSPORT)
							{
								for (boolean b : workDoing)
								{
									this.ship.addShipExp(ConfigHandler.expGain[6]);
								}
							}
							
							//check craning ending
							switch (this.craneMode)
							{
							case 0:  //no wait
								//check all work is false (no work to do)
								endCraning = true;
								
								for (boolean b : workDoing)
								{
									if (b)	//task is running
									{
										endCraning = false;
										break;
									}
								}
							break;
							case 1:  //wait forever
								if (checkWaitForever())
								{
									endCraning = true;
								}
							break;
							default: //wait X min
								int t = this.ship.getStateTimer(ID.T.CraneTime);
								
								if (t > waitTime)
								{
									endCraning = true;
								}
							break;
							}
							
							//craning end
							if (endCraning)
							{
								//emit redstone signal
								if (this.modeRedstone == 2)
								{
									this.tickRedstone = 2;
									this.world.notifyNeighborsOfStateChange(this.pos, ModBlocks.BlockCrane);
								}
								
								//set crane state
								this.ship.setStateMinor(ID.M.CraneState, 0);
								this.ship.setStateTimer(ID.T.CraneTime, 0);
								
								//set next waypoint
			  	  				if (EntityHelper.applyNextWaypoint(this, this.ship, false, 0))
			  	  				{
			  	  					//set follow dist
			  	  					this.ship.setStateMinor(ID.M.FollowMin, 2);
			  	  				}
			  	  				
			  	  				//player sound
			  	  				this.ship.playSound(ModSounds.SHIP_BELL, ConfigHandler.volumeShip * 1.5F, this.ship.getRNG().nextFloat() * 0.3F + 1F);
			  	  				
			  	  				//clear ship
			  	  				this.ship = null;
							}
						}
						catch (Exception e)
						{
							LogHelper.info("EXCEPTION: ship loading/unloading fail: "+e);
							e.printStackTrace();
							return;
						}
					}
				}//end 16 ticks
			}//is active
			
			//update checking
			if ((this.tick & 127) == 0)
			{
				//valid position
				if (this.chestPos.getY() > 0 || this.lastPos.getY() > 0 || this.nextPos.getY() > 0)
				{
					update = true;
				}
			}
			
			//can update
			if (update)
			{
				sendSyncPacket();
			}
		}//end server side
		//client side
		else
		{
			//valid tile
			if (this.world.getBlockState(this.pos).getBlock() != ModBlocks.BlockCrane)
			{
				this.invalidate();
				return;
			}
			
			this.tick++;
			if (this.partDelay > 0) this.partDelay--;
			
			//craning particle
			if (this.isActive && this.ship != null && this.partDelay <= 0)
			{
				this.partDelay = 128;
				
				double len = this.pos.getY() - this.ship.posY - 1D;
				if (len < 1D) len = 1D;
				
				ParticleHelper.spawnAttackParticleAt(pos.getX()+0.5D, pos.getY()-1D, pos.getZ()+0.5D,
																			len, 0D, 0.25D, (byte) 40);
			}
				
			//check every 16 ticks
			if ((this.tick & 15) == 0)
			{
				//player hold waypoint or target wrench
				EntityPlayer player = ClientProxy.getClientPlayer();
				ItemStack item = player.inventory.getCurrentItem();
				
				//if holding pointer, wrench, waypoint
				if (item != null && (item.getItem() instanceof ItemBlockWaypoint || item.getItem() == ModItems.TargetWrench ||
					(item.getItem() instanceof PointerItem && item.getItemDamage() < 3)))
				{
					//next point mark
					if (this.nextPos.getY() > 0)
					{
						double dx = this.nextPos.getX() - this.pos.getX();
						double dy = this.nextPos.getY() - this.pos.getY();
						double dz = this.nextPos.getZ() - this.pos.getZ();
						dx *= 0.01D;
						dy *= 0.01D;
						dz *= 0.01D;
								
						ParticleHelper.spawnAttackParticleAt(pos.getX()+0.5D, pos.getY()+0.5D, pos.getZ()+0.5D,
																						dx, dy, dz, (byte) 38);
					}
					
					//paired chest mark
					if (this.chestPos.getY() > 0)
					{
						double dx = this.chestPos.getX() - this.pos.getX();
						double dy = this.chestPos.getY() - this.pos.getY();
						double dz = this.chestPos.getZ() - this.pos.getZ();
						dx *= 0.01D;
						dy *= 0.01D;
						dz *= 0.01D;

						ParticleHelper.spawnAttackParticleAt(pos.getX()+0.5D, pos.getY()+0.5D, pos.getZ()+0.5D,
																						dx, dy, dz, (byte) 39);
					}
					
					if ((this.tick & 31) == 0)
					{
						//draw point text
						if (this.lastPos.getY() > 0 || this.nextPos.getY() > 0)
						{
							String postext1 = "";
							String postext2 = "";
							String postext3 = "";
							int len1 = 0;
							int len2 = 0;
							int len3 = 0;
							
							postext1 = "F: " + TextFormatting.LIGHT_PURPLE + this.lastPos.getX() + ", " + this.lastPos.getY() + ", " + this.lastPos.getZ();
							len1 = ClientProxy.getMineraft().getRenderManager().getFontRenderer().getStringWidth(postext1);
							postext2 = "T: " + TextFormatting.AQUA + this.nextPos.getX() + ", " + this.nextPos.getY() + ", " + this.nextPos.getZ();
							len2 = ClientProxy.getMineraft().getRenderManager().getFontRenderer().getStringWidth(postext2);
							if (len1 < len2) len1 = len2;
							postext3 = "C: " + TextFormatting.YELLOW + this.chestPos.getX() + ", " + this.chestPos.getY() + ", " + this.chestPos.getZ();
							len3 = ClientProxy.getMineraft().getRenderManager().getFontRenderer().getStringWidth(postext3);
							if (len1 < len3) len1 = len3;
							postext1 = postext1 + "\n" + TextFormatting.WHITE + postext2 + "\n" + TextFormatting.WHITE + postext3;
							
							ParticleHelper.spawnAttackParticleAt(postext1, this.pos.getX()+0.5D, this.pos.getY()+1.9D, this.pos.getZ()+0.5D, (byte) 0, 3, len1+1);
						}
					}//end every 32 ticks
				}//end holding item
			}//end every 16 ticks
		}//end client side
	}
	
	/** check wait forever ending
	 * 
	 *  1. loading: wait until ship's inventory full
	 *  2. unloading: wait until no specified item can unload
	 *  3. loading liquid: wait until all liquid container item is full or no valid container
	 *  4: unloading liquid: wait until all liquid container item is empty or no valid container
	 *  5: loading EU: same with liquid
	 *  6: unloading EU: same with liquid
	 */
	private boolean checkWaitForever()
	{
		boolean[] doneWork = new boolean[] {true, true, true, true};
		int i;
		
		if (this.ship != null)
		{
			CapaShipInventory inv = this.ship.getCapaShipInventory();
			
			//check loading item: ship's inventory is full
			if (this.enabLoad)
			{
				doneWork[0] = checkInventoryFull(this.ship.getCapaShipInventory());
			}
			
			//check unloading item: ship have no specified item
			if (this.enabUnload)
			{
				boolean allNull = true;
				
				for (i = 0; i < 9; i++)
				{
					ItemStack temp = getItemstackTemp(i, false);
					
					if (temp != null)
					{
						allNull = false;
						
						if (!this.getItemMode(i + 9))
						{
							//check items in ship inventory
							
							int slotid = matchTempItem(inv, temp);
							
							//get item
							if (slotid > 0)
							{
								doneWork[1] = false;
								break;
							}
						}
					}
					
					//if all temp slot are null = get any item except NotMode item
					if (i == 8 && allNull)
					{
						//check items in ship inventory
						int slotid = matchAnyItemExceptNotModeItem(inv, false);
						
						//get item
						if (slotid > 0)
						{
							doneWork[1] = false;
						}
					}//end temp all null
				}//end loop all temp slots
			}//end enable unload
			
			//check liquid mode
			if (this.rateLiquid > 0)
			{
				if (this.modeLiquid == 1)
				{
					//check all liquid container is full or no valid container
					doneWork[2] = checkFluidContainer(inv, true);
				}
				else if (this.modeLiquid == 2)
				{
					//check all liquid container is empty or no valid container
					doneWork[2] = checkFluidContainer(inv, false);
				}//end check liquid
			}
			
			//check energy mode TODO NYI
			
		}//end get ship
		
		//check all work is done
		for (boolean b : doneWork)
		{
			//work is unfinished
			if (!b) return false;
		}
		
		return true;
	}
	
	/*
	 * check all fluid container is full or no container
	 *   checkFull:
	 *     true: check all container is full or no container
	 *     false: check all container is empty or no container
	 */
	private static boolean checkFluidContainer(CapaShipInventory inv, boolean checkFull)
	{
		ItemStack stack = null;
		
		for (int i = ContainerShipInventory.SLOTS_SHIPINV; i < inv.getSizeInventoryPaged(); i++)
		{
			stack = inv.getStackInSlotWithoutPaging(i);
			
			if (stack != null)
			{
				//if item has fluid capability
				if (stack.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, EnumFacing.UP))
				{
					IFluidHandler fluid = stack.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, EnumFacing.UP);
					IFluidTankProperties[] tanks = fluid.getTankProperties();
					
					//check fluid amount in all tanks
					for (IFluidTankProperties tank : tanks)
					{
						FluidStack fstack = tank.getContents();
						
						//check container is full
						if (checkFull)
						{
							if (fstack == null || tank.canFill() && fstack.amount < tank.getCapacity())
							{
								return false;
							}
						}
						//check container is empty
						else
						{
							if (fstack != null && tank.canDrain() && fstack.amount > 0)
							{
								return false;
							}
						}
					}
				}//end get fluid capa
			}//end get stack
		}//end for all slots
		
		return true;
	}
	
	//check inventory is full
	private boolean checkInventoryFull(IInventory inv)
	{
		ItemStack item = null;
		int i = 0;
		
		//check inv type
		if(inv instanceof CapaShipInventory)
		{
			CapaShipInventory shipInv = (CapaShipInventory) inv;
			
			//get any empty slot = false
			for (i = ContainerShipInventory.SLOTS_SHIPINV; i < shipInv.getSizeInventoryPaged(); i++)
			{
				if (shipInv.getStackInSlotWithoutPaging(i) == null) return false;
			}
		}
		//invTo is vanilla chest
		else if (inv instanceof TileEntityChest)
		{
			//check main chest
			for (i = 0; i < inv.getSizeInventory(); i++)
			{
				if (inv.getStackInSlot(i) == null) return false;
			}
			
			//check adj chest
			TileEntityChest chest2 = getAdjChest((TileEntityChest) inv);
			
			if (chest2 != null)
			{
				for (i = 0; i < chest2.getSizeInventory(); i++)
				{
					if (chest2.getStackInSlot(i) == null) return false;
				}
			}
		}
		else
		{
			for (i = 0; i < inv.getSizeInventory(); i++)
			{
				if (inv.getStackInSlot(i) == null) return false;
			}
		}
		
		return true;
	}
	
	/**
	 * transfer liquid: crane tank <-> inventory
	 */
	private void applyPreLiquidTransfer(int mode)
	{
		if (this.chest != null)
		{
			IFluidHandler fh;
			FluidStack fs;
			boolean checkNextChest = true;
			
			//get fluid from chest inventory
			if (mode == 1)
			{
				//check all fluid container in chest
				for (int i = 0; i < this.chest.getSizeInventory(); i++)
				{
					fh = FluidUtil.getFluidHandler(this.chest.getStackInSlot(i));
					
					if (fh != null)
					{
						fs = FluidUtil.tryFluidTransfer(this.tank, fh, 64000, true);
						
						//only do 1 stack every 16 ticks
						if (fs != null)
						{
							checkNextChest = false;
							break;
						}
					}
				}
				
				//if chest is TileEntityChest, check nearby chest
				if (checkNextChest && this.chest instanceof TileEntityChest)
				{
					TileEntityChest chest2 = getAdjChest((TileEntityChest) this.chest);
					
					if (chest2 != null)
					{
						//check all fluid container in chest
						for (int i = 0; i < chest2.getSizeInventory(); i++)
						{
							fh = FluidUtil.getFluidHandler(chest2.getStackInSlot(i));
							
							if (fh != null)
							{
								fs = FluidUtil.tryFluidTransfer(this.tank, fh, 64000, true);
								
								//only do 1 stack every 16 ticks
								if (fs != null) break;
							}
						}
					}
				}
			}//end mode 1
			//transfer fluid to chest inventory
			else if (mode == 2 && this.tank.getFluid() != null)
			{
				//check all fluid container in chest
				for (int i = 0; i < this.chest.getSizeInventory(); i++)
				{
					fh = FluidUtil.getFluidHandler(this.chest.getStackInSlot(i));
					
					if (fh != null)
					{
						fs = FluidUtil.tryFluidTransfer(fh, this.tank, 16000, true);
						
						//only do 1 stack every 16 ticks
						if (fs != null)
						{
							checkNextChest = false;
							break;
						}
					}
				}
				
				//if chest is TileEntityChest, check nearby chest
				if (checkNextChest && this.chest instanceof TileEntityChest)
				{
					TileEntityChest chest2 = getAdjChest((TileEntityChest) this.chest);
					
					if (chest2 != null)
					{
						//check all fluid container in chest
						for (int i = 0; i < chest2.getSizeInventory(); i++)
						{
							fh = FluidUtil.getFluidHandler(chest2.getStackInSlot(i));
							
							if (fh != null)
							{
								fs = FluidUtil.tryFluidTransfer(fh, this.tank, 16000, true);
								
								//only do 1 stack every 16 ticks
								if (fs != null) break;
							}
						}
					}
				}
			}//end mode 2
		}//end get chest
	}
	
	/**
	 * liquid transport method, return true if some liquid is moved
	 */
	private boolean applyLiquidTransfer(int mode)
	{
		//get fluid by simulatly drain
		FluidStack f1;
		FluidStack f2;
		boolean moved = false;
		
		//crane tank to ship inventory
		if (mode == 1)
		{
			//f1 = remaining fluid after fill, f2 = fluid before fill
			//get fluid and null check
			f1 = this.tank.getFluid();
			
			if (f1 == null) return false;
			else if (f1.amount <= 0)
			{
				this.tank.setFluid(null);
				return false;
			}
			
			//get fluid amount, max = this.rateLiquid
			f1 = f1.copy();
			f1.amount = Math.min(this.rateLiquid, f1.amount);
			f2 = f1.copy();

			//fill all containers in ship inventory
			f1 = tryFillContainer(this.ship, f1);
			
			//liquid moved: X liquid -> null or amount changed
			if (f1 == null || f1.amount != f2.amount)
			{
				//set moved
				moved = true;
				
				//calc trans amount
				if (f1 != null) f2.amount = f2.amount - f1.amount;
				
				//drain liquid from tank
				this.tank.drainInternal(f2, true);
			}
		}
		//ship's inventory to crane tank
		else if (mode == 2)
		{
			//f1 = fluid drain from container, f2 = tank fluid before drain
			//get fluid and null check
			f1 = this.tank.getFluid();
			f1 = (f1 == null) ? null : f1.copy();
			
			//tank is full
			if (f1 != null && f1.amount >= this.tank.getCapacity())
			{
				return false;
			}
			else
			{
				//calc max drain amount
				int maxDrain = 0;
				
				if (f1 != null) maxDrain = Math.min(this.tank.getCapacity() - f1.amount, this.rateLiquid);
				else maxDrain = Math.min(this.tank.getCapacity(), this.rateLiquid);
				
				//check container in ship inventory, input and return f1 may be NULL
				f1 = tryDrainContainer(this.ship, f1, maxDrain);
				
				//no liquid moved
				if (f1 == null) return false;
				//liquid moved: null -> X liquid or amount changed
				else if (f1.amount > 0)
				{
					//set moved
					moved = true;
					
					//fill liquid to tank
					this.tank.fillInternal(f1, true);
				}
			}
		}
		
		return moved;
	}
	
	//fill container, return remaining fluid
	private static FluidStack tryFillContainer(BasicEntityShip ship, FluidStack fstack)
	{
		CapaShipInventory inv = ship.getCapaShipInventory();
		ItemStack stack;
		int amount;
		
		//fill all container in inventory
		for (int i = ContainerShipInventory.SLOTS_SHIPINV; i < inv.getSizeInventoryPaged(); i++)
		{
			stack = inv.getStackInSlotWithoutPaging(i);
			
			//only for container with stackSize = 1
			if (stack != null && stack.stackSize == 1)
			{
				amount = 0;
				
				//check item has fluid capa
				if (stack.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, EnumFacing.UP))
				{
					IFluidHandler fluid = stack.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, EnumFacing.UP);
					amount = fluid.fill(fstack, true);
				}//end get capa
				else if (stack.getItem() instanceof IFluidContainerItem)
				{
					amount = ((IFluidContainerItem) stack.getItem()).fill(stack, fstack, true);
				}
				
				//if fill success
				if (amount > 0)
				{
					fstack.amount -= amount;
					if (fstack.amount <= 0) break;
				}
			}//end get item
		}//end for all slots
		
		return fstack;
	}
	
	//drain container, return remaining fluid
	private static FluidStack tryDrainContainer(BasicEntityShip ship, FluidStack targetFluid, int maxDrain)
	{
		CapaShipInventory inv = ship.getCapaShipInventory();
		ItemStack stack;
		FluidStack drainTemp;
		FluidStack drainTotal = null;
		
		if (targetFluid != null) targetFluid.amount = maxDrain;
		
		//fill all container in inventory
		for (int i = ContainerShipInventory.SLOTS_SHIPINV; i < inv.getSizeInventoryPaged(); i++)
		{
			stack = inv.getStackInSlotWithoutPaging(i);
			drainTemp = null;
			
			if (stack != null && stack.stackSize == 1)
			{
				//check item has fluid capa
				if (stack.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, EnumFacing.UP))
				{
					IFluidHandler fluid = stack.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, EnumFacing.UP);

					//drain targetFluid from container
					if (targetFluid != null) drainTemp = fluid.drain(targetFluid, true);
					//targetFluid is null -> set the first liquid found as targetFluid
					else drainTemp = fluid.drain(maxDrain, true);
				}//end has fluid capa
				else if (stack.getItem() instanceof IFluidContainerItem)
				{
					//drain targetFluid from container
					if (targetFluid != null && targetFluid.isFluidEqual(((IFluidContainerItem) stack.getItem()).getFluid(stack)))
						drainTemp = ((IFluidContainerItem) stack.getItem()).drain(stack, targetFluid.amount, true);
					//targetFluid is null -> set the first liquid found as targetFluid
					else if (targetFluid == null)
						drainTemp = ((IFluidContainerItem) stack.getItem()).drain(stack, maxDrain, true);
				}
				
				//add temp to total drain
				if (drainTemp != null)
				{
					//targetFluid amount--
					if (targetFluid != null)
					{
						targetFluid.amount -= drainTemp.amount;
					}
					else
					{
						targetFluid = drainTemp.copy();
						targetFluid.amount = maxDrain - drainTemp.amount;
					}
					
					//drainTotal amount++
					if (drainTotal == null) drainTotal = drainTemp.copy();
					else drainTotal.amount += drainTemp.amount;
				}
				
				if (targetFluid != null && targetFluid.amount <= 0) break;
			}//end get item
		}//end for all slots
		
		return drainTotal;
	}
	
	/**
	 * EU transport method, return true if some energy is moved
	 */
	private boolean applyEnergyTransfer(int mode)
	{
		//TODO NYI
		return false;
	}
	
	/** item loading / unloading
	 * 
	 *  return true = item moved; false = no item can move
	 */
	private boolean applyItemTransfer(boolean isLoading)
	{
		IInventory invFrom = null;
		IInventory invTo = null;
		ItemStack tempitem = null;
		ItemStack moveitem = null;
		boolean allNull = true;  //all temp slots are null = move all items
		boolean moved = false;
		int i, j;
		int slotid = -1;
		
		/** get target slot */
		//check temp slots
		for (i = 0; i < 9; i++)
		{
			//set inv
			if (isLoading)
			{
				invFrom = chest;
				invTo = ship.getCapaShipInventory();
			}
			else
			{
				invTo = chest;
				invFrom = ship.getCapaShipInventory();
			}
			
			//get load item type
			tempitem = getItemstackTemp(i, isLoading);
			
			//temp != null
			if (tempitem != null)
			{
				allNull = false;
				
				//check target item exist in invFrom
				slotid = matchTempItem(invFrom, tempitem);
				
				//check target item in adj chest if no item in main chest
				if (slotid < 0 && invFrom instanceof TileEntityChest)
				{
					TileEntityChest chest2 = getAdjChest((TileEntityChest) invFrom);
					
					if (chest2 != null)
					{
						invFrom = chest2;
						slotid = matchTempItem(invFrom, tempitem);
					}
				}
				
				/** move target item */
				if (slotid >= 0)
				{
					//move item
					if (invFrom instanceof CapaShipInventory) moveitem = ((CapaShipInventory) invFrom).getStackInSlotWithoutPaging(slotid);
					else moveitem = invFrom.getStackInSlot(slotid);
					moved = moveItemstackToInv(invTo, moveitem);
					
					//check item size
					if (moved && moveitem.stackSize <= 0)
					{
						if (invFrom instanceof CapaShipInventory) ((CapaShipInventory) invFrom).setInventorySlotWithoutPaging(slotid, null);
						else invFrom.setInventorySlotContents(slotid, null);
					}
					
					//end moving item (1 itemstack per method call)
					if (moved) break;
				}
			}//end temp != null
			else
			{
				//all slots are null
				if (i == 8 && allNull)
				{
					slotid = matchAnyItemExceptNotModeItem(invFrom, isLoading);
					
					//check target item in adj chest if no item in main chest
					if (slotid < 0 && invFrom instanceof TileEntityChest)
					{
						TileEntityChest chest2 = getAdjChest((TileEntityChest) invFrom);
						
						if (chest2 != null)
						{
							invFrom = chest2;
							slotid = matchAnyItemExceptNotModeItem(invFrom, isLoading);
						}
					}
					
					/** move target item */
					if (slotid >= 0)
					{
						//move item
						if (invFrom instanceof CapaShipInventory) moveitem = ((CapaShipInventory) invFrom).getStackInSlotWithoutPaging(slotid);
						else moveitem = invFrom.getStackInSlot(slotid);
						moved = moveItemstackToInv(invTo, moveitem);
						
						//check item size
						if (moved && moveitem.stackSize <= 0)
						{
							if (invFrom instanceof CapaShipInventory) ((CapaShipInventory) invFrom).setInventorySlotWithoutPaging(slotid, null);
							else invFrom.setInventorySlotContents(slotid, null);
						}
					}
				}
			}//end temp is null
		}//end all temp slots
		
		return moved;
	}
	
	//move itemstack to inv with inv type checking, return true = item moved
	private boolean moveItemstackToInv(IInventory inv, ItemStack moveitem)
	{
		boolean moved = false;
		
		//move item to inv
		if (moveitem != null)
		{
			//invTo is ship inv
			if (inv instanceof CapaShipInventory)
			{
				moved = mergeItemStack(inv, moveitem);
			}
			//invTo is vanilla chest
			else if (inv instanceof TileEntityChest)
			{
				TileEntityChest chest = (TileEntityChest) inv;
				TileEntityChest chest2 = null;
				
				//move to main chest
				moved = mergeItemStack(chest, moveitem);
				
				//move fail, check adj chest
				if (!moved)
				{
					//get adj chest
					chest2 = getAdjChest(chest);
					
					//move to adj chest
					if (chest2 != null) moved = mergeItemStack(chest2, moveitem);
				}//end move to adj chest
			}
			//other normal inv
			else
			{
				moved = mergeItemStack(inv, moveitem);
			}
			
		}//end move item
		
		return moved;
	}
	
	//get adj chest for TileEntityChest
	private TileEntityChest getAdjChest(TileEntityChest chest)
	{
		TileEntityChest chest2 = null;
		
		if (chest != null && !chest.isInvalid())
		{
			//check adj chest valid
			chest.checkForAdjacentChests();
			
			//get adj chest
			chest2 = chest.adjacentChestXNeg;
			if (chest2 == null)
			{
				chest2 = chest.adjacentChestXPos;
				if (chest2 == null)
				{
					chest2 = chest.adjacentChestZNeg;
					if (chest2 == null) chest2 = chest.adjacentChestZPos;
				}
			}
		}
		
		if (chest2 != null && chest2.isInvalid()) return null;
		
		return chest2;
	}
	
	//get itemstack temp from loading or unloading slots
	private ItemStack getItemstackTemp(int i, boolean isLoadingTemp)
	{
		//check slot is notMode
		if (this.getItemMode(isLoadingTemp ? i : i + 9))
		{
			return null;
		}
		
		//get loading temp
		if (isLoadingTemp)
		{
			return this.itemHandler.getStackInSlot(i);
		}
		//get unloading temp
		else
		{
			return this.itemHandler.getStackInSlot(i + 9);
		}
	}
	
	//merge itemstack to slot
	private boolean mergeItemStack(IInventory inv, ItemStack itemstack)
	{
		ItemStack slotstack;
		boolean movedItem = false;
        int k = 0;
        int startid = 0;
        int maxSize = inv.getSizeInventory();

        //init slots for ship inventory
        if(inv instanceof CapaShipInventory)
        {
        	//start at inv slots
        	startid = ContainerShipInventory.SLOTS_SHIPINV;
        	
        	//get slot size by pages
        	maxSize = ((CapaShipInventory) inv).getSizeInventoryPaged();
        }

        //is stackable item
        if (itemstack.isStackable())
        {
        	k = startid;
        	
        	//loop all slots until stacksize = 0
            while (itemstack.stackSize > 0 && k < maxSize)
            {
				if (inv instanceof CapaShipInventory) slotstack = ((CapaShipInventory) inv).getStackInSlotWithoutPaging(k);
				else slotstack = inv.getStackInSlot(k);

                //is same item, merge to slot
                if (slotstack != null && slotstack.getItem() == itemstack.getItem() &&
                	(!itemstack.getHasSubtypes() || itemstack.getItemDamage() == slotstack.getItemDamage()) &&
                   ItemStack.areItemStackTagsEqual(itemstack, slotstack))
                {
                    int l = slotstack.stackSize + itemstack.stackSize;

                    //merge: total size < max size
                    if (l <= itemstack.getMaxStackSize())
                    {
                        itemstack.stackSize = 0;
                        slotstack.stackSize = l;
                        movedItem = true;
                    }
                    //merge: move item to slot stack
                    else if (slotstack.stackSize < itemstack.getMaxStackSize())
                    {
                        itemstack.stackSize -= itemstack.getMaxStackSize() - slotstack.stackSize;
                        slotstack.stackSize = itemstack.getMaxStackSize();
                        movedItem = true;
                    }
                }

                //next slot
                ++k;
            }//end loop all slots
        }//end is stackable

        //no stack can merge, find empty slot
        if (itemstack.stackSize > 0)
        {
        	k = startid;

        	//loop all slots to find empty slot
            while (k < maxSize)
            {
				if (inv instanceof CapaShipInventory) slotstack = ((CapaShipInventory) inv).getStackInSlotWithoutPaging(k);
				else slotstack = inv.getStackInSlot(k);

                //find empty slot
                if (slotstack == null)
                {
					if (inv instanceof CapaShipInventory) ((CapaShipInventory) inv).setInventorySlotWithoutPaging(k, itemstack.copy());
					else inv.setInventorySlotContents(k, itemstack.copy());
                    itemstack.stackSize = 0;
                    movedItem = true;
                    break;
                }

                //next slot
                ++k;
            }//end loop all slots
        }

        return movedItem;
    }
	
	//get slot id in inventory that match target item
	private int matchTempItem(IInventory inv, ItemStack target)
	{
		ItemStack getitem = null;
		int slotid = 0;
		int startid = 0;
		int maxSize = inv.getSizeInventory();
		
		//init slots for ship inventory
        if (inv instanceof CapaShipInventory)
        {
        	//start at inv slots
        	startid = ContainerShipInventory.SLOTS_SHIPINV;
        	
        	//get max size by page
        	maxSize = ((CapaShipInventory) inv).getSizeInventoryPaged();
        }
		
		//match taget item
		if (target != null)
		{
			//check slots
			for (slotid = startid; slotid < maxSize; slotid++)
			{
				if (inv instanceof CapaShipInventory) getitem = ((CapaShipInventory) inv).getStackInSlotWithoutPaging(slotid);
				else getitem = inv.getStackInSlot(slotid);
				
				if (getitem != null)
				{
					//check item type
					if (getitem.getItem() == target.getItem())
					{
						//check both nbt and meta
						if (checkNbt && checkMetadata)
						{
							if (ItemStack.areItemStackTagsEqual(getitem, target) &&
								getitem.getItemDamage() == target.getItemDamage())
							{
								return slotid;
							}
						}
						//check nbt only
						else if (checkNbt)
						{
							if (ItemStack.areItemStackTagsEqual(getitem, target)) return slotid;
						}
						//check meta only
						else if (checkMetadata)
						{
							if (getitem.getItemDamage() == target.getItemDamage()) return slotid;
						}
						//dont check nbt and meta
						else
						{
							return slotid;
						}
					}
					else
					{
						//check ore dict
						if (checkOredict)
						{
							int[] a = OreDictionary.getOreIDs(target);
							int[] b = OreDictionary.getOreIDs(getitem);
							
							if (a.length > 0 && b.length > 0 && a[0] == b[0]) return slotid;
						}
					}
				}//end get chest item
			}//for all slots in chest
		}
		//temp null, get any item
		else
		{
			for (slotid = startid; slotid < maxSize; slotid++)
			{
				if (inv instanceof CapaShipInventory) getitem = ((CapaShipInventory) inv).getStackInSlotWithoutPaging(slotid);
				else getitem = inv.getStackInSlot(slotid);
				
				if (getitem != null)
				{
					return slotid;
				}
			}
		}
		
		return -1;
	}
	
	//get any item except NotMode item
	private int matchAnyItemExceptNotModeItem(IInventory inv, boolean isLoading)
	{
		ItemStack getitem = null;
		int slotid = 0;
		int startid = 0;
		int maxSize = inv.getSizeInventory();
		
		//init slots for ship inventory
        if (inv instanceof CapaShipInventory)
        {
        	//start at inv slots
        	startid = ContainerShipInventory.SLOTS_SHIPINV;
        	
        	//get max size by page
        	maxSize = ((CapaShipInventory) inv).getSizeInventoryPaged();
        }
        
		for (slotid = startid; slotid < maxSize; slotid++)
		{
			if (inv instanceof CapaShipInventory) getitem = ((CapaShipInventory) inv).getStackInSlotWithoutPaging(slotid);
			else getitem = inv.getStackInSlot(slotid);
			
			if (getitem != null)
			{
				if (checkNotModeItem(slotid, getitem, isLoading) >= 0) return slotid;
			}
		}

		return -1;
	}	
	//if item is in NotMode slot, return -1
	private int checkNotModeItem(int slotid, ItemStack item, boolean isLoading)
	{
		ItemStack temp = null;
		int slotStart = isLoading ? 0 : 9;
		int slotEnd = isLoading ? 9 : 18;
		
		for (int i = slotStart; i < slotEnd; i++)
		{
			if (getItemMode(i))
			{
				temp = this.itemHandler.getStackInSlot(i);
				
				if (temp != null)
				{
					//check item type
					if (item.getItem() == temp.getItem())
					{
						//check both nbt and meta
						if (checkNbt && checkMetadata)
						{
							if (ItemStack.areItemStackTagsEqual(item, temp) &&
								item.getItemDamage() == temp.getItemDamage())
							{
								return -1;
							}
						}
						//check nbt only
						else if (checkNbt)
						{
							if (ItemStack.areItemStackTagsEqual(item, temp)) return -1;
						}
						//check meta only
						else if (checkMetadata)
						{
							if (item.getItemDamage() == temp.getItemDamage()) return -1;
						}
						//dont check nbt and meta
						else
						{
							return -1;
						}
					}
					else
					{
						//check ore dict
						if (checkOredict)
						{
							int[] a = OreDictionary.getOreIDs(item);
							int[] b = OreDictionary.getOreIDs(temp);
							
							if (a.length > 0 && b.length > 0 && a[0] == b[0])
							{
								return -1;
							}
						}
					}
				}
			}
		}
		
		//pass checking, return slot id
		return slotid;
	}
	
	//check ship under crane waiting for craning
	private void checkCraningShip()
	{
		AxisAlignedBB box = new AxisAlignedBB(pos.getX() - 7D, pos.getY() - 7D, pos.getZ() - 7D,
											  pos.getX() + 7D, pos.getY() + 7D, pos.getZ() + 7D);
        List<BasicEntityShip> slist = this.world.getEntitiesWithinAABB(BasicEntityShip.class, box);

        if (slist != null && !slist.isEmpty())
        {
        	//get craning ship
        	for (BasicEntityShip s : slist)
        	{
        		if (s.getStateMinor(ID.M.CraneState) == 2 &&
        			s.getGuardedPos(0) == pos.getX() &&
        			s.getGuardedPos(1) == pos.getY() &&
        			s.getGuardedPos(2) == pos.getZ())
        		{
        			setShipData(s);
        			return;
        		}
        	}
        	
        	//no craning ship, get waiting ship
        	for (BasicEntityShip s : slist)
        	{
        		if(s.getStateMinor(ID.M.CraneState) == 1 &&
         		   s.getGuardedPos(0) == pos.getX() &&
         		   s.getGuardedPos(1) == pos.getY() &&
         		   s.getGuardedPos(2) == pos.getZ())
        		{
        			setShipData(s);
         			return;
         		}
        	}
        }
        else
        {
        	if (this.ship != null)
        	{
        		this.ship = null;
            	this.sendSyncPacket();
        	}
        }
	}
	
	//set ship data like drum level
	protected void setShipData(BasicEntityShip ship)
	{
		this.ship = ship;
		this.ship.setStateMinor(ID.M.CraneState, 2);			//set crane state = craning
		this.ship.getShipNavigate().tryMoveToXYZ(pos.getX()+0.5D, pos.getY()-2D, pos.getZ()+0.5D, 0.5D);
		
		int[] drumNum = calcDrumLevel(ship, 0);
		
		//check liquid drum level
		this.rateLiquid = drumNum[1] * ConfigHandler.drumLiquid[1] + drumNum[0] * ConfigHandler.drumLiquid[0];
		this.rateLiquid = this.rateLiquid * 16 * ((int)((float)ship.getLevel() * 0.1F) + 1);
		
		//check EU storage level
		if (CommonProxy.activeIC2)
		{
			drumNum = calcDrumLevel(ship, 1);
			this.rateEU = drumNum[1] * ConfigHandler.drumEU[1] + drumNum[0] * ConfigHandler.drumEU[0];
			this.rateEU = this.rateEU * 16 * ((int)((float)ship.getLevel() * 0.1F) + 1);
		}
		
		//sync to client
		this.sendSyncPacket();
	}
	
	//type: 0:fluid, 1:EU, return int[2]: 0:#equips, 1:#enchantments
	protected int[] calcDrumLevel(BasicEntityShip ship, int type)
	{
		int[] num = new int[] {0, 0};
		CapaShipInventory inv = ship.getCapaShipInventory();
		
		for (int i = 0; i < 6; i++)
		{
			ItemStack stack = inv.getStackInSlotWithoutPaging(i);
			
			if (stack != null && stack.getItem() == ModItems.EquipDrum)
			{
				//check liquid drum
				if ((type == 0 && stack.getItemDamage() == 1) ||
					(type == 1 && stack.getItemDamage() == 2))
				{
					num[0]++;
					num[1] += EnchantHelper.calcEnchantNumber(stack);
				}
			}//end get drum
		}//end for all equip slot
		
		return num;
	}
	
	@Override
  	public ItemStack getStackInSlot(int i)
	{
  		return this.itemHandler.getStackInSlot(i);
  	}
	
	@Override
  	public ItemStack decrStackSize(int i, int j)
	{
		return null;
	}
	
	@Override
  	public void setInventorySlotContents(int i, ItemStack stack)
	{
  		this.itemHandler.setStackInSlot(i, stack);
  		
  		if (stack != null)
  		{
  			stack.stackSize = 1;
  		}	
  	}
	
	//每格可放的最大數量上限
  	@Override
  	public int getInventoryStackLimit()
  	{
  		return 0;
  	}
  	
  	//使用管線/漏斗輸入時呼叫, 不適用於手動置入
  	@Override
  	public boolean canInsertItem(int id, ItemStack stack, EnumFacing side)
  	{
  		return false;
  	}
  	
  	//get waiting time (min)
  	public static int getWaitTimeInMin(int mode)
  	{
  		if (mode >= 6 && mode <= 15)
  		{
			return mode - 5;
		}
		else if (mode >= 16 && mode <= 19)
		{
			return (mode - 16) * 5 + 15;
		}
		else if (mode >= 20 && mode <= 22)
		{
			return (mode - 20) * 10 + 40;
		}
		else if (mode >= 23 && mode <= 25)
		{
			return (mode - 23) * 60 + 120;
		}
		else
		{
			return 0;
		}
  	}

	@Override
	public void setWpStayTime(int time) {}

	@Override
	public int getWpStayTime()
	{
		return 0;
	}
	
	//set slot is NOT loading/unloading mode
	public void setItemMode(int slotID, boolean notMode)
	{
		int slot = 1 << (slotID - 1);
		
		//set bit 1
		if (notMode)
		{
			this.modeItem = this.modeItem | slot;
		}
		//set bit 0
		else
		{
			this.modeItem = this.modeItem & ~slot;
		}
	}
	
	//check slot is notMode
	public boolean getItemMode(int slotID)
	{
		return ((modeItem >> (slotID - 1)) & 1) == 1 ? true : false;
	}
	
	//getter, setter
	public int getRedMode()
	{
		return this.modeRedstone;
	}
	
	public int getRedTick()
	{
		return this.tickRedstone;
	}
	
	public void setShip(BasicEntityShip ship)
	{
		this.ship = ship;
	}
	
	public BasicEntityShip getShip()
	{
		return this.ship;
	}
	
	/** FIELD相關方法
	 *  使其他mod或class也能存取該tile的內部值
	 *  ex: gui container可用get/setField來更新數值
	 *  
	 *  field id:
	 *  0:ship eid, 1:craneTime, 2:isActive, 3:checkMeta, 4:checkDict
	 *  5:craneMode, 6:enabLoad, 7:enabUnload, 8:checkNbt, 9:itemMode
	 *  10:redMode
	 */
	@Override
	public int getField(int id)
	{
		switch (id)
		{
		case 0:
			return this.ship == null ? 0 : this.ship.getEntityId();
		case 1:
			return this.ship == null ? 0 : this.ship.getStateTimer(ID.T.CraneTime);
		case 2:
			return this.isActive ? 1 : 0;
		case 3:
			return this.checkMetadata ? 1 : 0;
		case 4:
			return this.checkOredict ? 1 : 0;
		case 5:
			return this.craneMode;
		case 6:
			return this.enabLoad ? 1 : 0;
		case 7:
			return this.enabUnload ? 1 : 0;
		case 8:
			return this.checkNbt ? 1 : 0;
		case 9:
			return this.modeItem;
		case 10:
			return this.modeRedstone;
		case 11:
			return this.playerUID;
		case 12:
			return this.modeLiquid;
		case 13:
			return this.modeEnergy;
		default:
			return 0;
		}
	}

	@Override
	public void setField(int id, int value)
	{
		switch (id)
		{
		case 0:
		case 1:
		break;
		case 2:
			this.isActive = value == 0 ? false : true;
		break;
		case 3:
			this.checkMetadata = value == 0 ? false : true;
		break;
		case 4:
			this.checkOredict = value == 0 ? false : true;
		break;
		case 5:
			this.craneMode = value;
		break;
		case 6:
			this.enabLoad = value == 0 ? false : true;
		break;
		case 7:
			this.enabUnload = value == 0 ? false : true;
		break;
		case 8:
			this.checkNbt = value == 0 ? false : true;
		break;
		case 9:
			this.modeItem = value;
		break;
		case 10:
			this.modeRedstone = value;
		break;
		case 11:
			this.playerUID = value;
		break;
		case 12:
			this.modeLiquid = value;
		break;
		case 13:
			this.modeEnergy = value;
		break;
		}
	}

	@Override
	public int getFieldCount()
	{
		return 14;
	}
	
	@Override
	public boolean isUsableByPlayer(EntityPlayer player)
	{
		//check tile owner
		if (BlockHelper.checkTileOwner(player, this))
		{
			return super.isUsableByPlayer(player);
		}
		
		return false;
	}

	
}
