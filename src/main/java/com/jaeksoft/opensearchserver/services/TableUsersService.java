/*
 * Copyright 2017-2018 Emmanuel Keller / Jaeksoft
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.jaeksoft.opensearchserver.services;

import com.jaeksoft.opensearchserver.model.ActiveStatus;
import com.jaeksoft.opensearchserver.model.PermissionRecord;
import com.jaeksoft.opensearchserver.model.TableUserRecord;
import com.jaeksoft.opensearchserver.model.UserRecord;
import com.qwazr.database.TableServiceInterface;
import com.qwazr.database.annotations.TableRequestResultRecords;
import com.qwazr.database.model.TableQuery;
import com.qwazr.database.model.TableRequest;
import com.qwazr.utils.LoggerUtils;
import com.qwazr.utils.StringUtils;
import com.qwazr.utils.concurrent.ThreadUtils;
import io.undertow.security.idm.Account;
import io.undertow.security.idm.Credential;
import io.undertow.security.idm.PasswordCredential;

import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotAcceptableException;
import javax.ws.rs.NotAuthorizedException;
import javax.ws.rs.NotFoundException;
import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TableUsersService extends BaseTableService<TableUserRecord> implements UsersService {

    private final static Logger LOGGER = LoggerUtils.getLogger(TableUsersService.class);

    private final ConfigService configService;

    public TableUsersService(final ConfigService configService, final TableServiceInterface tableServiceInterface)
        throws NoSuchMethodException {
        super(tableServiceInterface, TableUserRecord.class);
        this.configService = configService;
    }

    @Override
    public Account verify(Account account) {
        try {
            if (!(account instanceof UserAccount))
                return null;
            return new UserAccount(getUserById(((UserAccount) account).getPrincipal().getId()));
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Account verify failed for " + account, e);
            return null;
        }
    }

    public Integer getUserCount() {
        return tableService.getTableStatus().getNumRows();
    }

    /**
     * User login
     *
     * @param email         the email address of the user
     * @param clearPassword the clear password
     * @return
     */
    private UserRecord login(final String email, final String clearPassword) {
        if (email == null || clearPassword == null)
            return null;
        final UserRecord user = getUserByEmail(email);
        if (user != null && user.getStatus() == ActiveStatus.ENABLED &&
            user.matchPassword(configService.getApplicationSalt(), clearPassword))
            return user;
        ThreadUtils.sleep(2, TimeUnit.SECONDS);
        throw new NotAuthorizedException("Authentication failure");
    }

    @Override
    public Account verify(final String email, final Credential credential) {
        if (StringUtils.isBlank(email) || credential == null)
            return null;
        if (!(credential instanceof PasswordCredential))
            return null;
        final String password = new String(((PasswordCredential) credential).getPassword());
        if (StringUtils.isBlank(password))
            return null;
        try {
            return new UserAccount(login(email, password));
        } catch (NotAuthorizedException e) {
            return null;
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Account verify failed for " + email, e);
            return null;
        }
    }

    public UserRecord verify(final String email, final String clearPassword) {
        final UserAccount account = (UserAccount) verify(email, new PasswordCredential(clearPassword.toCharArray()));
        return account == null ? null : account.getPrincipal();
    }

    @Override
    public Account verify(Credential credential) {
        return null;
    }

    @Override
    public TableUserRecord getUserById(final UUID id) {
        try {
            return tableService.getRow(Objects.requireNonNull(id, "The user UUID is null").toString(), columnsSet);
        } catch (IOException | ReflectiveOperationException e) {
            throw new InternalServerErrorException("Cannot get user by id", e);
        }
    }

    public Map<UserRecord, PermissionRecord> getUsersByIds(
        final TableRequestResultRecords<PermissionRecord> permissions) {
        try {
            if (permissions == null || permissions.records == null)
                return Collections.emptyMap();
            final Set<String> idSet = new LinkedHashSet<>();
            permissions.records.forEach(permission -> idSet.add(permission.getUserId().toString()));
            final List<TableUserRecord> userList = tableService.getRows(columnsSet, idSet);
            if (userList == null || userList.isEmpty())
                return Collections.emptyMap();
            final Map<UserRecord, PermissionRecord> results = new LinkedHashMap<>();
            final Iterator<PermissionRecord> permissionsIterator = permissions.records.iterator();
            userList.forEach(account -> results.put(account, permissionsIterator.next()));
            return results;
        } catch (IOException | ReflectiveOperationException e) {
            throw new InternalServerErrorException("Cannot get users by ids", e);
        }
    }

    @Override
    public boolean isSingleSignOn() {
        return false;
    }

    public TableUserRecord getUserByEmail(final String email) {
        try {
            final TableRequestResultRecords<TableUserRecord> result = tableService.queryRows(
                TableRequest.from(0, 1).column(columnsArray).query(new TableQuery.StringTerm("email", email)).build());
            return result != null && result.count != null && result.count == 1 ? result.records.get(0) : null;
        } catch (IOException | ReflectiveOperationException e) {
            throw new InternalServerErrorException("Cannot get user by email", e);
        }
    }

    @Override
    public int getUsers(final int start, final int rows, final Consumer<UserRecord> records) {
        try {
            final TableRequestResultRecords<TableUserRecord> results =
                tableService.queryRows(TableRequest.from(start, rows).column(columnsArray).build());
            if (results == null || results.count == null)
                return 0;
            results.records.forEach(records::accept);
            return results.count.intValue();
        } catch (IOException | ReflectiveOperationException e) {
            throw new InternalServerErrorException("Cannot get user list", e);
        }
    }

    public synchronized UUID createUser(final String userEmail) {
        final TableUserRecord existingUser = getUserByEmail(userEmail);
        if (existingUser != null)
            return existingUser.getId();
        final TableUserRecord newUser = TableUserRecord.of().email(userEmail).build();
        tableService.upsertRow(newUser.id, newUser);
        return newUser.getId();
    }

    private TableUserRecord getExistingUser(final UUID userId) {
        final TableUserRecord user = getUserById(userId);
        if (user == null)
            throw new NotFoundException("User not found: " + userId);
        return user;
    }

    @Override
    public void setPassword(final UUID userId, final String newPassword) {
        final TableUserRecord oldUser = getExistingUser(userId);
        if (!UsersService.checkPasswordStrength(newPassword))
            throw new NotAcceptableException(PASSWORD_STRENGTH_MESSAGE);
        final TableUserRecord newUser =
            TableUserRecord.of(oldUser).password(configService.getApplicationSalt(), newPassword).build();
        tableService.upsertRow(newUser.id, newUser);
    }

    public boolean updateStatus(final UUID userId, final ActiveStatus status) {
        final TableUserRecord oldUser = getExistingUser(userId);
        if (oldUser.getStatus() != null && oldUser.getStatus() == status)
            return false;
        final TableUserRecord newUser = TableUserRecord.of(oldUser).status(status).build();
        tableService.upsertRow(newUser.id, newUser);
        return true;
    }

}
