package org.qortal.repository.hsqldb;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.qortal.data.naming.NameData;
import org.qortal.repository.DataException;
import org.qortal.repository.NameRepository;

public class HSQLDBNameRepository implements NameRepository {

	protected HSQLDBRepository repository;

	public HSQLDBNameRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	@Override
	public NameData fromName(String name) throws DataException {
		String sql = "SELECT owner, data, registered_when, updated_when, reference, is_for_sale, sale_price, creation_group_id FROM Names WHERE name = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, name)) {
			if (resultSet == null)
				return null;

			String owner = resultSet.getString(1);
			String data = resultSet.getString(2);
			long registered = resultSet.getLong(3);

			// Special handling for possibly-NULL "updated" column
			Long updated = resultSet.getLong(4);
			if (updated == 0 && resultSet.wasNull())
				updated = null;

			byte[] reference = resultSet.getBytes(5);
			boolean isForSale = resultSet.getBoolean(6);

			Long salePrice = resultSet.getLong(7);
			if (salePrice == 0 && resultSet.wasNull())
				salePrice = null;

			int creationGroupId = resultSet.getInt(8);

			return new NameData(owner, name, data, registered, updated, reference, isForSale, salePrice, creationGroupId);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch name info from repository", e);
		}
	}

	@Override
	public boolean nameExists(String name) throws DataException {
		try {
			return this.repository.exists("Names", "name = ?", name);
		} catch (SQLException e) {
			throw new DataException("Unable to check for name in repository", e);
		}
	}

	@Override
	public List<NameData> getAllNames(Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(256);

		sql.append("SELECT name, data, owner, registered_when, updated_when, reference, is_for_sale, sale_price, creation_group_id FROM Names ORDER BY name");

		if (reverse != null && reverse)
			sql.append(" DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<NameData> names = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString())) {
			if (resultSet == null)
				return names;

			do {
				String name = resultSet.getString(1);
				String data = resultSet.getString(2);
				String owner = resultSet.getString(3);
				long registered = resultSet.getLong(4);

				// Special handling for possibly-NULL "updated" column
				Long updated = resultSet.getLong(5);
				if (updated == 0 && resultSet.wasNull())
					updated = null;

				byte[] reference = resultSet.getBytes(6);
				boolean isForSale = resultSet.getBoolean(7);

				Long salePrice = resultSet.getLong(8);
				if (salePrice == 0 && resultSet.wasNull())
					salePrice = null;

				int creationGroupId = resultSet.getInt(9);

				names.add(new NameData(owner, name, data, registered, updated, reference, isForSale, salePrice, creationGroupId));
			} while (resultSet.next());

			return names;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch names from repository", e);
		}
	}

	@Override
	public List<NameData> getNamesForSale(Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(512);

		sql.append("SELECT name, data, owner, registered_when, updated_when, reference, sale_price, creation_group_id FROM Names WHERE is_for_sale = TRUE ORDER BY name");

		if (reverse != null && reverse)
			sql.append(" DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<NameData> names = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString())) {
			if (resultSet == null)
				return names;

			do {
				String name = resultSet.getString(1);
				String data = resultSet.getString(2);
				String owner = resultSet.getString(3);
				long registered = resultSet.getLong(4);

				// Special handling for possibly-NULL "updated" column
				Long updated = resultSet.getLong(5);
				if (updated == 0 && resultSet.wasNull())
					updated = null;

				byte[] reference = resultSet.getBytes(6);
				boolean isForSale = true;

				Long salePrice = resultSet.getLong(7);
				if (salePrice == 0 && resultSet.wasNull())
					salePrice = null;

				int creationGroupId = resultSet.getInt(8);

				names.add(new NameData(owner, name, data, registered, updated, reference, isForSale, salePrice, creationGroupId));
			} while (resultSet.next());

			return names;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch names from repository", e);
		}
	}

	@Override
	public List<NameData> getNamesByOwner(String owner, Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(512);

		sql.append("SELECT name, data, registered_when, updated_when, reference, is_for_sale, sale_price, creation_group_id FROM Names WHERE owner = ? ORDER BY name");

		if (reverse != null && reverse)
			sql.append(" DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<NameData> names = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), owner)) {
			if (resultSet == null)
				return names;

			do {
				String name = resultSet.getString(1);
				String data = resultSet.getString(2);
				long registered = resultSet.getLong(3);

				// Special handling for possibly-NULL "updated" column
				Long updated = resultSet.getLong(4);
				if (updated == 0 && resultSet.wasNull())
					updated = null;

				byte[] reference = resultSet.getBytes(5);
				boolean isForSale = resultSet.getBoolean(6);

				Long salePrice = resultSet.getLong(7);
				if (salePrice == 0 && resultSet.wasNull())
					salePrice = null;

				int creationGroupId = resultSet.getInt(8);

				names.add(new NameData(owner, name, data, registered, updated, reference, isForSale, salePrice, creationGroupId));
			} while (resultSet.next());

			return names;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account's names from repository", e);
		}
	}

	@Override
	public List<String> getRecentNames(long startTimestamp) throws DataException {
		String sql = "SELECT name FROM RegisterNameTransactions JOIN Names USING (name) "
				+ "JOIN Transactions USING (signature) "
				+ "WHERE created_when >= ?";

		List<String> names = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql, startTimestamp)) {
			if (resultSet == null)
				return names;

			do {
				String name = resultSet.getString(1);

				names.add(name);
			} while (resultSet.next());

			return names;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch recent names from repository", e);
		}
	}

	@Override
	public void save(NameData nameData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("Names");

		saveHelper.bind("owner", nameData.getOwner()).bind("name", nameData.getName()).bind("data", nameData.getData())
				.bind("registered_when", nameData.getRegistered()).bind("updated_when", nameData.getUpdated())
				.bind("reference", nameData.getReference())
				.bind("is_for_sale", nameData.getIsForSale()).bind("sale_price", nameData.getSalePrice())
				.bind("creation_group_id", nameData.getCreationGroupId());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save name info into repository", e);
		}
	}

	@Override
	public void delete(String name) throws DataException {
		try {
			this.repository.delete("Names", "name = ?", name);
		} catch (SQLException e) {
			throw new DataException("Unable to delete name info from repository", e);
		}
	}

}
