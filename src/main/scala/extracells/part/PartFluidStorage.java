package extracells.part;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.Vec3d;

import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import appeng.api.AEApi;
import appeng.api.config.AccessRestriction;
import appeng.api.config.SecurityPermissions;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.events.MENetworkCellArrayUpdate;
import appeng.api.networking.events.MENetworkChannelsChanged;
import appeng.api.networking.events.MENetworkEventSubscribe;
import appeng.api.networking.events.MENetworkPowerStatusChange;
import appeng.api.networking.events.MENetworkStorageEvent;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartCollisionHelper;
import appeng.api.parts.PartItemStack;
import appeng.api.storage.ICellContainer;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.StorageChannel;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.util.AECableType;
import extracells.container.ContainerBusFluidStorage;
import extracells.gui.GuiBusFluidStorage;
import extracells.inventory.HandlerPartStorageFluid;
import extracells.network.packet.other.IFluidSlotPartOrBlock;
import extracells.network.packet.other.PacketFluidSlot;
import extracells.network.packet.part.PacketBusFluidStorage;
import extracells.util.PermissionUtil;
import extracells.util.inventory.ECPrivateInventory;
import extracells.util.inventory.IInventoryUpdateReceiver;

public class PartFluidStorage extends PartECBase implements ICellContainer, IInventoryUpdateReceiver, IFluidSlotPartOrBlock {

	private HashMap<FluidStack, Integer> fluidList = new HashMap<FluidStack, Integer>();
	private int priority = 0;
	protected HandlerPartStorageFluid handler = new HandlerPartStorageFluid(this);
	private Fluid[] filterFluids = new Fluid[54];
	private AccessRestriction access = AccessRestriction.READ_WRITE;
	private ECPrivateInventory upgradeInventory = new ECPrivateInventory("", 1, 1, this) {

		@Override
		public boolean isItemValidForSlot(int i, ItemStack itemStack) {
			return itemStack != null && AEApi.instance().definitions().materials().cardInverter().isSameAs(itemStack);
		}
	};

	@Override
	public void getDrops( List<ItemStack> drops, boolean wrenched) {
		for (ItemStack stack : upgradeInventory.slots) {
			if (stack == null)
				continue;
			drops.add(stack);
		}
	}

	@Override
	public ItemStack getItemStack(PartItemStack type) {
		ItemStack stack = super.getItemStack(type);
		if (type.equals(PartItemStack.WRENCH))
			stack.getTagCompound().removeTag("upgradeInventory");
		return stack;
	}

	@Override
	public void blinkCell(int slot) {}

	@Override
	public float getCableConnectionLength(AECableType aeCableType) {
		return 3.0F;
	}

	@Override
	public void getBoxes(IPartCollisionHelper bch) {
		bch.addBox(2, 2, 15, 14, 14, 16);
		bch.addBox(4, 4, 14, 12, 12, 15);
		bch.addBox(5, 5, 13, 11, 11, 14);
	}

	@Override
	public List<IMEInventoryHandler> getCellArray(StorageChannel channel) {
		List<IMEInventoryHandler> list = new ArrayList<IMEInventoryHandler>();
		if (channel == StorageChannel.FLUIDS) {
			list.add(this.handler);
		}
		updateNeighborFluids();
		return list;
	}

	@Override
	public Object getClientGuiElement(EntityPlayer player) {
		return new GuiBusFluidStorage(this, player);
	}

	@Override
	public int getLightLevel() {
		return 0;
	}

	@Override
	public double getPowerUsage() {
		return 1.0D;
	}

	@Override
	public int getPriority() {
		return this.priority;
	}

	@Override
	public Object getServerGuiElement(EntityPlayer player) {
		return new ContainerBusFluidStorage(this, player);
	}

	public ECPrivateInventory getUpgradeInventory() {
		return this.upgradeInventory;
	}

	@Override
	public boolean onActivate(EntityPlayer player, EnumHand hand, Vec3d pos) {
		return PermissionUtil.hasPermission(player, SecurityPermissions.BUILD, (IPart) this) && super.onActivate(player, hand, pos);
	}

	@Override
	public void onInventoryChanged() {
		this.handler.setInverted(AEApi.instance().definitions().materials().cardInverter().isSameAs(this.upgradeInventory.getStackInSlot(0)));
		saveData();
	}

	@Override
	public void onNeighborChanged() {
		this.handler.onNeighborChange();
		IGridNode node = getGridNode();
		if (node != null) {
			IGrid grid = node.getGrid();
			if (grid != null && this.wasChanged()) {
				grid.postEvent(new MENetworkCellArrayUpdate());
				node.getGrid().postEvent(new MENetworkStorageEvent(getGridBlock().getFluidMonitor(), StorageChannel.FLUIDS));
				node.getGrid().postEvent(new MENetworkCellArrayUpdate());
			}
			getHost().markForUpdate();
		}
	}

	@MENetworkEventSubscribe
	public void powerChange(MENetworkPowerStatusChange event) {
		IGridNode node = getGridNode();
		if (node != null) {
			boolean isNowActive = node.isActive();
			if (isNowActive != isActive()) {
				setActive(isNowActive);
				onNeighborChanged();
				getHost().markForUpdate();
			}
		}
		node.getGrid().postEvent(new MENetworkStorageEvent(getGridBlock().getFluidMonitor(), StorageChannel.FLUIDS));
		node.getGrid().postEvent(new MENetworkCellArrayUpdate());
	}

	@Override
	public void readFromNBT(NBTTagCompound data) {
		super.readFromNBT(data);
		this.priority = data.getInteger("priority");
		for (int i = 0; i < 9; i++) {
			this.filterFluids[i] = FluidRegistry.getFluid(data.getString("FilterFluid#" + i));
		}
		if (data.hasKey("access")) {
			try {
				this.access = AccessRestriction.valueOf(data.getString("access"));
			} catch (Throwable e) {}
		}
		this.upgradeInventory.readFromNBT(data.getTagList("upgradeInventory", 10));
		onInventoryChanged();
		onNeighborChanged();
		this.handler.setPrioritizedFluids(this.filterFluids);
		this.handler.setAccessRestriction(this.access);
	}

	/*@SideOnly(Side.CLIENT)
	@Override
	public void renderInventory(IPartRenderHelper rh, RenderBlocks renderer) {
		Tessellator ts = Tessellator.instance;

		IIcon side = TextureManager.STORAGE_SIDE.getTexture();
		rh.setTexture(side, side, side,
				TextureManager.STORAGE_FRONT.getTextures()[0], side, side);
		rh.setBounds(2, 2, 15, 14, 14, 16);
		rh.renderInventoryBox(renderer);

		rh.setBounds(4, 4, 14, 12, 12, 15);
		rh.renderInventoryBox(renderer);
		rh.setBounds(2, 2, 15, 14, 14, 16);
		rh.setInvColor(AEColor.Cyan.blackVariant);
		ts.setBrightness(15 << 20 | 15 << 4);
		rh.renderInventoryFace(TextureManager.STORAGE_FRONT.getTextures()[1],
				ForgeDirection.SOUTH, renderer);

		rh.setBounds(5, 5, 13, 11, 11, 14);
		renderInventoryBusLights(rh, renderer);
	}

	@SideOnly(Side.CLIENT)
	@Override
	public void renderStatic(int x, int y, int z, IPartRenderHelper rh,
			RenderBlocks renderer) {
		Tessellator ts = Tessellator.instance;

		IIcon side = TextureManager.STORAGE_SIDE.getTexture();
		rh.setTexture(side, side, side,
				TextureManager.STORAGE_FRONT.getTexture(), side, side);
		rh.setBounds(2, 2, 15, 14, 14, 16);
		rh.renderBlock(x, y, z, renderer);

		ts.setColorOpaque_I(getHost().getColor().blackVariant);
		if (isActive())
			ts.setBrightness(15 << 20 | 15 << 4);
		rh.renderFace(x, y, z, TextureManager.STORAGE_FRONT.getTextures()[1],
				ForgeDirection.SOUTH, renderer);
		rh.setBounds(4, 4, 14, 12, 12, 15);
		rh.renderBlock(x, y, z, renderer);

		rh.setBounds(5, 5, 13, 11, 11, 14);
		renderStaticBusLights(x, y, z, rh, renderer);
	}*/

	@Override
	public void saveChanges(IMEInventory cellInventory) {
		saveData();
	}

	public void sendInformation(EntityPlayer player) {
		new PacketFluidSlot(Arrays.asList(this.filterFluids))
				.sendPacketToPlayer(player);
		new PacketBusFluidStorage(player, this.access, true)
				.sendPacketToPlayer(player);
	}

	@Override
	public void setFluid(int _index, Fluid _fluid, EntityPlayer _player) {
		this.filterFluids[_index] = _fluid;
		this.handler.setPrioritizedFluids(this.filterFluids);
		sendInformation(_player);
		saveData();
	}

	public void updateAccess(AccessRestriction access) {
		this.access = access;
		this.handler.setAccessRestriction(access);
		onNeighborChanged();
	}

	@MENetworkEventSubscribe
	public void updateChannels(MENetworkChannelsChanged channel) {
		IGridNode node = getGridNode();
		if (node != null) {
			boolean isNowActive = node.isActive();
			if (isNowActive != isActive()) {
				setActive(isNowActive);
				onNeighborChanged();
				getHost().markForUpdate();
			}
		}
		node.getGrid().postEvent(
				new MENetworkStorageEvent(getGridBlock().getFluidMonitor(),
						StorageChannel.FLUIDS));
		node.getGrid().postEvent(new MENetworkCellArrayUpdate());
	}

	@Override
	public void writeToNBT(NBTTagCompound data) {
		super.writeToNBT(data);
		data.setInteger("priority", this.priority);
		for (int i = 0; i < this.filterFluids.length; i++) {
			Fluid fluid = this.filterFluids[i];
			if (fluid != null)
				data.setString("FilterFluid#" + i, fluid.getName());
			else
				data.setString("FilterFluid#" + i, "");
		}
		data.setTag("upgradeInventory", this.upgradeInventory.writeToNBT());
		data.setString("access", this.access.name());
	}
	
	private void updateNeighborFluids(){
		fluidList.clear();
		if(access == AccessRestriction.READ || access == AccessRestriction.READ_WRITE){
			for(IAEFluidStack stack : handler.getAvailableItems(AEApi.instance().storage().createFluidList())){
				FluidStack s = stack.getFluidStack().copy();
				fluidList.put(s, s.amount);
			}
		}
	}
	
	private boolean wasChanged(){
		HashMap<FluidStack, Integer> fluids = new HashMap<FluidStack, Integer>();
		for(IAEFluidStack stack : handler.getAvailableItems(AEApi.instance().storage().createFluidList())){
			FluidStack s = stack.getFluidStack();
			fluids.put(s, s.amount);
		}
		return !fluids.equals(fluidList);
	}
}
